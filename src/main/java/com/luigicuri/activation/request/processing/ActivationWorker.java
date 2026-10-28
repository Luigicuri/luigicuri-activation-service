package com.luigicuri.activation.request.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.luigicuri.activation.provider.ProviderClient;
import com.luigicuri.activation.shared.correlation.CorrelationId;

/**
 * Sends PENDING requests to the provider (ADR-0001). The table is the queue: a request committed by
 * the API is picked up here even if the instance that created it crashed right after the commit.
 */
@Component
public class ActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivationWorker.class);

    private final ActivationProcessingService processingService;
    private final ProviderClient providerClient;

    public ActivationWorker(ActivationProcessingService processingService, ProviderClient providerClient) {
        this.processingService = processingService;
        this.providerClient = providerClient;
    }

    @Scheduled(fixedDelayString = "${activation.processing.poll-interval}")
    public void processDueRequests() {
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
            var result = providerClient.activate(claim.toCommand());
            processingService.recordResult(claim, result);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Activation request {} was updated concurrently; result not recorded", claim.id());
        } catch (RuntimeException e) {
            // The request stays PROCESSING and is released by the reconciliation once its lease expires.
            log.error("Unexpected error processing activation request {}", claim.id(), e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
