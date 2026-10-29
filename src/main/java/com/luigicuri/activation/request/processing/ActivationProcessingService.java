package com.luigicuri.activation.request.processing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

import com.luigicuri.activation.provider.ProviderResult;
import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;

/**
 * The short database transactions around a provider call (ADR-0001). The call itself happens in
 * {@link ActivationWorker}, between {@link #claimDue} and {@link #recordResult}, with no transaction open.
 */
@Service
public class ActivationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessingService.class);

    private final ActivationRequestRepository repository;
    private final RetryPolicy retryPolicy;
    private final ProcessingProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ActivationProcessingService(ActivationRequestRepository repository, RetryPolicy retryPolicy,
                                       ProcessingProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** Moves a batch of due requests to PROCESSING and commits, so the claim survives the provider call. */
    @Transactional
    public List<ClaimedActivation> claimDue() {
        var now = clock.instant();
        var due = repository.lockDueForProcessing(now, properties.batchSize());
        due.forEach(request -> request.startProcessing(now));
        return due.stream().map(ClaimedActivation::of).toList();
    }

    @Transactional
    public void recordResult(ClaimedActivation claim, ProviderResult result) {
        var request = repository.findById(claim.id()).orElseThrow();
        if (request.getStatus() != ActivationStatus.PROCESSING || request.getAttempts() != claim.attempt()) {
            // The lease expired and the request was released (and maybe claimed again) meanwhile.
            log.warn("Discarding outdated provider result for request {} (attempt {}); it is now {} on attempt {}",
                    claim.id(), claim.attempt(), request.getStatus(), request.getAttempts());
            return;
        }

        var now = clock.instant();
        switch (result) {
            case ProviderResult.Success success -> {
                request.complete(now);
                log.info("Activation request {} completed on attempt {}", request.getId(), request.getAttempts());
            }
            case ProviderResult.PermanentFailure failure -> {
                request.fail(failure.reason(), now);
                log.warn("Activation request {} failed permanently: {}", request.getId(), failure.reason());
            }
            case ProviderResult.TransientFailure failure ->
                    retryOrFail(request, failure.reason(), failure.retryAfter(), now);
            case ProviderResult.CredentialsRejected rejected -> {
                var nextAttemptAt = now.plus(properties.credentialsRejectedDelay());
                request.postpone(nextAttemptAt, now);
                log.error("{}: request {} postponed to {} without using an attempt. Check PROVIDER_API_KEY.",
                        rejected.reason(), request.getId(), nextAttemptAt);
            }
        }
        countTransition(request);
    }

    /** Releases requests stuck in PROCESSING past their lease. The lost call counts as an attempt. */
    @Transactional
    public int releaseExpiredClaims() {
        var now = clock.instant();
        var expired = repository.lockExpiredProcessing(now.minus(properties.leaseTimeout()), properties.batchSize());
        expired.forEach(request -> {
            retryOrFail(request, "Provider call did not complete within the processing lease", Optional.empty(), now);
            countTransition(request);
        });
        return expired.size();
    }

    /** Requests leaving PROCESSING, by resulting status: completions, failures and retries over time (R9.3). */
    private void countTransition(ActivationRequest request) {
        meterRegistry.counter("activation.requests.transitions", "to", request.getStatus().name()).increment();
    }

    private void retryOrFail(ActivationRequest request, String reason, Optional<Duration> retryAfter, Instant now) {
        retryPolicy.nextAttemptAt(request.getAttempts(), retryAfter, now).ifPresentOrElse(
                nextAttemptAt -> {
                    request.scheduleRetry(nextAttemptAt, now);
                    log.warn("Activation request {} attempt {} failed ({}); retrying at {}",
                            request.getId(), request.getAttempts(), reason, nextAttemptAt);
                },
                () -> {
                    request.fail("Gave up after %d attempts. Last error: %s".formatted(request.getAttempts(), reason),
                            now);
                    log.error("Activation request {} failed after {} attempts: {}",
                            request.getId(), request.getAttempts(), reason);
                });
    }
}
