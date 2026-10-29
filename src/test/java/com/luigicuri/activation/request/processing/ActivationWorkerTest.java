package com.luigicuri.activation.request.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** How the worker isolates failures between requests of the same batch, and what it measures. */
@ExtendWith(MockitoExtension.class)
class ActivationWorkerTest {

    @Mock
    ActivationProcessingService processingService;

    @Mock
    ProviderClient providerClient;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    ActivationWorker worker;

    private final ClaimedActivation first = claim("trace-1");
    private final ClaimedActivation second = claim("trace-2");

    @BeforeEach
    void setUp() {
        worker = new ActivationWorker(processingService, providerClient, meterRegistry);
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

    private static ClaimedActivation claim(String correlationId) {
        return new ClaimedActivation(UUID.randomUUID(), 1, "CUST-1", "INTERNET-500", correlationId);
    }
}
