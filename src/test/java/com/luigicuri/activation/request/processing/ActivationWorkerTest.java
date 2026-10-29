package com.luigicuri.activation.request.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.luigicuri.activation.provider.ProviderClient;
import com.luigicuri.activation.provider.ProviderResult;
import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.shared.correlation.CorrelationId;
import com.luigicuri.activation.shared.tracing.TraceContexts;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** How the worker isolates failures between requests of the same batch, guards the provider and measures it. */
@ExtendWith(MockitoExtension.class)
class ActivationWorkerTest {

    @Mock
    ActivationProcessingService processingService;

    @Mock
    ProviderClient providerClient;

    @Mock
    TraceContexts traceContexts;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** Opens after a single failed call, so tests can trip it within one batch. */
    static final CircuitBreakerProperties TIGHT = new CircuitBreakerProperties(50, 1, 1, Duration.ofMinutes(1), 1);

    CircuitBreaker circuitBreaker = new ProcessingConfiguration().providerCircuitBreaker(TIGHT);

    ActivationWorker worker;

    private final ClaimedActivation first = claim("trace-1");
    private final ClaimedActivation second = claim("trace-2");

    @BeforeEach
    void setUp() {
        // Runs the work inline, as the real TraceContexts does around it
        lenient().doAnswer(invocation -> invocation.getArgument(3, Supplier.class).get())
                .when(traceContexts).inSpan(any(), any(), any(), any());
        worker = new ActivationWorker(processingService, providerClient, meterRegistry, traceContexts,
                circuitBreaker, TIGHT);
    }

    @Test
    void unexpectedErrorOnOneRequestDoesNotStopTheBatch() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(first.toCommand())).willThrow(new IllegalStateException("bug"));
        given(providerClient.activate(second.toCommand())).willReturn(new ProviderResult.Success());

        worker.processDueRequests();

        verify(processingService).recordResult(second, new ProviderResult.Success());
    }

    @Test
    void concurrentUpdateWhileRecordingIsSkippedAndTheBatchContinues() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(any())).willReturn(new ProviderResult.Success());
        willThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, first.id()))
                .given(processingService).recordResult(eq(first), any());

        worker.processDueRequests();

        verify(processingService).recordResult(second, new ProviderResult.Success());
    }

    @Test
    void providerCallsAreTimedByOutcome() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(first.toCommand())).willReturn(new ProviderResult.Success());
        given(providerClient.activate(second.toCommand()))
                .willReturn(new ProviderResult.PermanentFailure("HTTP 400"));

        worker.processDueRequests();

        assertThat(meterRegistry.timer("activation.provider.calls", "outcome", "success").count()).isEqualTo(1);
        assertThat(meterRegistry.timer("activation.provider.calls", "outcome", "permanent_failure").count())
                .isEqualTo(1);
    }

    @Test
    void rejectedCredentialsAreTimedSeparately() {
        given(processingService.claimDue()).willReturn(List.of(first));
        given(providerClient.activate(any()))
                .willReturn(new ProviderResult.CredentialsRejected("Provider rejected our credentials (HTTP 401)"));

        worker.processDueRequests();

        assertThat(meterRegistry.timer("activation.provider.calls", "outcome", "credentials_rejected").count())
                .isEqualTo(1);
    }

    @Test
    void correlationIdOfTheCreatingRequestIsUsedDuringTheCallAndClearedAfterwards() {
        var traceDuringCall = new AtomicReference<String>();
        given(processingService.claimDue()).willReturn(List.of(first));
        willAnswer(invocation -> {
            traceDuringCall.set(MDC.get(CorrelationId.MDC_KEY));
            return new ProviderResult.Success();
        }).given(providerClient).activate(any());

        worker.processDueRequests();

        assertThat(traceDuringCall).hasValue("trace-1");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void requestWithoutCorrelationIdIsTracedByItsOwnId() {
        var withoutTrace = claim(null);
        var traceDuringCall = new AtomicReference<String>();
        given(processingService.claimDue()).willReturn(List.of(withoutTrace));
        willAnswer(invocation -> {
            traceDuringCall.set(MDC.get(CorrelationId.MDC_KEY));
            return new ProviderResult.Success();
        }).given(providerClient).activate(any());

        worker.processDueRequests();

        assertThat(traceDuringCall).hasValue(withoutTrace.id().toString());
    }

    @Test
    void reconciliationDelegatesToTheProcessingService() {
        given(processingService.releaseExpiredClaims()).willReturn(2).willReturn(0);

        worker.releaseExpiredClaims();
        worker.releaseExpiredClaims();

        verify(processingService, times(2)).releaseExpiredClaims();
    }

    @Test
    @DisplayName("R9.2 each request is processed in a span continuing the trace that created it")
    void processingContinuesTheCreatingTrace() {
        var traced = new ClaimedActivation(UUID.randomUUID(), 1, "CUST-1", "INTERNET-500", "trace-1",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        given(processingService.claimDue()).willReturn(List.of(traced));
        given(providerClient.activate(any())).willReturn(new ProviderResult.Success());

        worker.processDueRequests();

        verify(traceContexts).inSpan(eq("activation process"), eq(traced.traceParent()),
                eq(Map.of("activation.request_id", traced.id().toString())), any());
    }

    @Test
    @DisplayName("open circuit: nothing is claimed, so no request uses an attempt")
    void openCircuitClaimsNothing() {
        circuitBreaker.transitionToOpenState();

        worker.processDueRequests();

        verify(processingService, never()).claimDue();
    }

    @Test
    @DisplayName("circuit opening mid-batch: the rest of the batch goes back to the queue without a call")
    void circuitOpeningMidBatchReleasesTheRestWithoutCalling() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(first.toCommand()))
                .willReturn(new ProviderResult.TransientFailure("Provider returned HTTP 503", Optional.empty()));

        worker.processDueRequests();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verify(providerClient, never()).activate(second.toCommand());
        verify(processingService).releaseWithoutCall(second, TIGHT.waitDurationInOpenState());
    }

    @Test
    @DisplayName("rejected credentials are a provider-side failure and open the circuit")
    void rejectedCredentialsOpenTheCircuit() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(first.toCommand()))
                .willReturn(new ProviderResult.CredentialsRejected("Provider rejected our credentials (HTTP 401)"));

        worker.processDueRequests();

        verify(processingService).releaseWithoutCall(second, TIGHT.waitDurationInOpenState());
    }

    @Test
    @DisplayName("a request the provider refuses is a healthy answer: the circuit stays closed")
    void refusedRequestsDoNotOpenTheCircuit() {
        given(processingService.claimDue()).willReturn(List.of(first, second));
        given(providerClient.activate(any())).willReturn(new ProviderResult.PermanentFailure("HTTP 400"));

        worker.processDueRequests();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(processingService, times(2)).recordResult(any(), any());
    }

    @Test
    @DisplayName("half-open trial call that blows up gives its permission back")
    void unexpectedErrorDuringTrialCallDoesNotLeakThePermission() {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        given(processingService.claimDue()).willReturn(List.of(first)).willReturn(List.of(second));
        given(providerClient.activate(first.toCommand())).willThrow(new IllegalStateException("bug"));
        given(providerClient.activate(second.toCommand())).willReturn(new ProviderResult.Success());

        worker.processDueRequests();
        worker.processDueRequests();

        verify(processingService).recordResult(second, new ProviderResult.Success());
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static ClaimedActivation claim(String correlationId) {
        return new ClaimedActivation(UUID.randomUUID(), 1, "CUST-1", "INTERNET-500", correlationId, null);
    }
}
