package com.luigicuri.activation.request.processing;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.luigicuri.activation.provider.ProviderClient;
import com.luigicuri.activation.provider.ProviderResult;
import com.luigicuri.activation.shared.correlation.CorrelationId;
import com.luigicuri.activation.shared.tracing.TraceContexts;

/**
 * Sends PENDING requests to the provider (ADR-0001). The table is the queue: a request committed by
 * the API is picked up here even if the instance that created it crashed right after the commit.
 *
 * <p>A circuit breaker guards the provider (ADR-0003): while it is open nothing is claimed, so an outage
 * or a rejected credential does not use up the requests' attempts.
 */
@Component
public class ActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivationWorker.class);

    private final ActivationProcessingService processingService;
    private final ProviderClient providerClient;
    private final MeterRegistry meterRegistry;
    private final TraceContexts traceContexts;
    private final CircuitBreaker circuitBreaker;
    private final CircuitBreakerProperties circuitBreakerProperties;

    public ActivationWorker(ActivationProcessingService processingService, ProviderClient providerClient,
                            MeterRegistry meterRegistry, TraceContexts traceContexts, CircuitBreaker circuitBreaker,
                            CircuitBreakerProperties circuitBreakerProperties) {
        this.processingService = processingService;
        this.providerClient = providerClient;
        this.meterRegistry = meterRegistry;
        this.traceContexts = traceContexts;
        this.circuitBreaker = circuitBreaker;
        this.circuitBreakerProperties = circuitBreakerProperties;
    }

    @Scheduled(fixedDelayString = "${activation.processing.poll-interval}")
    public void processDueRequests() {
        // Asking for a permission is also what moves an open circuit to half-open once its wait is over.
        if (!circuitBreaker.tryAcquirePermission()) {
            return;
        }
        circuitBreaker.releasePermission();
        for (var claim : processingService.claimDue()) {
            process(claim);
        }
    }

    @Scheduled(fixedDelayString = "${activation.processing.reconciliation-interval}")
    public void releaseExpiredClaims() {
        int released = processingService.releaseExpiredClaims();
        if (released > 0) {
            log.warn("Released {} activation request(s) stuck in PROCESSING past their lease", released);
        }
    }

    private void process(ClaimedActivation claim) {
        // Restores the correlation id of the HTTP request that created it, so logs and the provider call share it.
        MDC.put(CorrelationId.MDC_KEY, claim.correlationId() != null ? claim.correlationId() : claim.id().toString());
        try {
            // Continues the trace of the POST that created the request, across the asynchronous hop (R9.2)
            traceContexts.inSpan("activation process", claim.traceParent(),
                    Map.of("activation.request_id", claim.id().toString()), () -> {
                        callProvider(claim);
                        return null;
                    });
        } catch (OptimisticLockingFailureException e) {
            log.warn("Activation request {} was updated concurrently; result not recorded", claim.id());
        } catch (RuntimeException e) {
            // The request stays PROCESSING and is released by the reconciliation once its lease expires.
            log.error("Unexpected error processing activation request {}", claim.id(), e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void callProvider(ClaimedActivation claim) {
        if (!circuitBreaker.tryAcquirePermission()) {
            // The circuit opened during this batch: hand the request back without calling or using its attempt.
            processingService.releaseWithoutCall(claim, circuitBreakerProperties.waitDurationInOpenState());
            return;
        }
        var sample = Timer.start(meterRegistry);
        long start = System.nanoTime();
        ProviderResult result;
        try {
            result = providerClient.activate(claim.toCommand());
        } catch (RuntimeException e) {
            circuitBreaker.releasePermission();
            throw e;
        }
        sample.stop(meterRegistry.timer("activation.provider.calls", "outcome", outcomeOf(result)));
        recordOutcome(result, System.nanoTime() - start);
        processingService.recordResult(claim, result);
    }

    /** Only failures of the provider itself open the circuit; a request it rejects is a healthy answer. */
    private void recordOutcome(ProviderResult result, long nanos) {
        switch (result) {
            case ProviderResult.TransientFailure failure ->
                    circuitBreaker.onError(nanos, TimeUnit.NANOSECONDS, new ProviderFailure(failure.reason()));
            case ProviderResult.CredentialsRejected rejected ->
                    circuitBreaker.onError(nanos, TimeUnit.NANOSECONDS, new ProviderFailure(rejected.reason()));
            case ProviderResult.Success success -> circuitBreaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
            case ProviderResult.PermanentFailure failure -> circuitBreaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /** Provider latency and error rate per outcome (R9.3). */
    private static String outcomeOf(ProviderResult result) {
        return switch (result) {
            case ProviderResult.Success success -> "success";
            case ProviderResult.PermanentFailure failure -> "permanent_failure";
            case ProviderResult.TransientFailure failure -> "transient_failure";
            case ProviderResult.CredentialsRejected rejected -> "credentials_rejected";
        };
    }

    /** What the circuit breaker records for a failed call; the stack trace would say nothing useful. */
    static final class ProviderFailure extends RuntimeException {
        ProviderFailure(String reason) {
            super(reason, null, false, false);
        }
    }
}
