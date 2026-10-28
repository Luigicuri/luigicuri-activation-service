package com.luigicuri.activation.request.processing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.luigicuri.activation.provider.ProviderResult;
import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;
import com.luigicuri.activation.support.IntegrationTest;

/**
 * R5 against PostgreSQL and a WireMock provider. The worker is invoked directly (one polling cycle per
 * call) instead of waiting for the scheduler, so every scenario is deterministic.
 */
@IntegrationTest
class ActivationProcessingIT {

    private static final String ACTIVATE = "/provider/v1/activate";

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", provider::baseUrl);
        // Generous enough for the first call to a cold WireMock, short enough to keep the timeout test fast
        registry.add("provider.read-timeout", () -> "1s");
        registry.add("activation.retry.max-attempts", () -> "3");
    }

    @Autowired
    ActivationWorker worker;

    @Autowired
    ActivationProcessingService processingService;

    @Autowired
    ActivationRequestRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE activation_request");
    }

    @Test
    @DisplayName("2xx -> COMPLETED, called once with the request id as Idempotency-Key")
    void successCompletesTheRequest() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        var request = pendingRequest("EXT-OK");

        worker.processDueRequests();

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(stored.getAttempts()).isEqualTo(1);
        provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString()))
                .withHeader("X-Correlation-Id", equalTo("trace-EXT-OK")));
    }

    @Test
    @DisplayName("400 -> FAILED, no retry")
    void badRequestFailsWithoutRetry() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(400)));
        var request = pendingRequest("EXT-400");

        worker.processDueRequests();
        makeEveryRequestDue();
        worker.processDueRequests();

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo("Provider rejected the request (HTTP 400)");
        provider.verify(1, callsFor(request));
    }

    @Test
    @DisplayName("500 -> back to PENDING with backoff, then 2xx -> COMPLETED")
    void serverErrorIsRetriedUntilSuccess() {
        provider.stubFor(post(ACTIVATE).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        provider.stubFor(post(ACTIVATE).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        var request = pendingRequest("EXT-500");
        var beforeFirstCall = Instant.now();

        worker.processDueRequests();

        var afterFailure = reload(request);
        assertThat(afterFailure.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getNextAttemptAt()).isAfterOrEqualTo(beforeFirstCall.truncatedTo(ChronoUnit.MILLIS));

        worker.processDueRequests();
        assertThat(reload(request).getStatus())
                .as("not retried before nextAttemptAt").isIn(ActivationStatus.PENDING, ActivationStatus.COMPLETED);

        makeEveryRequestDue();
        worker.processDueRequests();

        var completed = reload(request);
        assertThat(completed.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(completed.getAttempts()).isEqualTo(2);
        provider.verify(2, postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString())));
    }

    @Test
    @DisplayName("timeout -> back to PENDING; the retry reuses the same Idempotency-Key")
    void timeoutIsRetriedWithTheSameIdempotencyKey() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(2_500)));
        var request = pendingRequest("EXT-TIMEOUT");

        worker.processDueRequests();

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getNextAttemptAt()).isAfter(stored.getUpdatedAt().minusMillis(1));
    }

    @Test
    @DisplayName("429 with Retry-After -> next attempt scheduled when the provider asked")
    void throttlingHonoursRetryAfter() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));
        var request = pendingRequest("EXT-429");

        worker.processDueRequests();

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(stored.getNextAttemptAt())
                .isCloseTo(stored.getUpdatedAt().plusSeconds(60), within(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("transient failures until max attempts -> FAILED")
    void givesUpAfterMaxAttempts() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503)));
        var request = pendingRequest("EXT-GIVE-UP");

        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.processDueRequests();
            makeEveryRequestDue();
        }
        worker.processDueRequests();

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(stored.getAttempts()).isEqualTo(3);
        assertThat(stored.getFailureReason()).isEqualTo("Gave up after 3 attempts. Last error: Provider returned HTTP 503");
        provider.verify(3, callsFor(request));
    }

    @Test
    @DisplayName("R5.8 SKIP LOCKED: rows locked by one worker are skipped by another, not waited for")
    void concurrentWorkersNeverClaimTheSameRequest() throws Exception {
        for (int i = 0; i < 6; i++) {
            pendingRequest("EXT-LOCK-" + i);
        }
        var firstWorkerHoldsLocks = new CountDownLatch(1);
        var releaseFirstWorker = new CountDownLatch(1);
        List<UUID> firstBatch = new ArrayList<>();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                repository.lockDueForProcessing(Instant.now(), 4).forEach(r -> firstBatch.add(r.getId()));
                firstWorkerHoldsLocks.countDown();
                await(releaseFirstWorker);
            }));
            await(firstWorkerHoldsLocks);

            var secondBatch = processingService.claimDue().stream().map(ClaimedActivation::id).toList();

            releaseFirstWorker.countDown();
            firstWorker.get(10, TimeUnit.SECONDS);

            assertThat(firstBatch).hasSize(4);
            assertThat(secondBatch).hasSize(2).doesNotContainAnyElementsOf(firstBatch);
        }
    }

    @Test
    @DisplayName("R5.7 a request stuck in PROCESSING past its lease is released for another attempt")
    void expiredClaimIsReleased() {
        var request = pendingRequest("EXT-STUCK");
        var claim = processingService.claimDue().getFirst();
        jdbcTemplate.update("UPDATE activation_request SET processing_started_at = now() - interval '10 minutes'");

        int released = processingService.releaseExpiredClaims();

        var stored = reload(request);
        assertThat(released).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(claim.attempt());
    }

    @Test
    @DisplayName("a result arriving after the lease expired and the request was re-claimed is discarded")
    void outdatedResultIsDiscarded() {
        var request = pendingRequest("EXT-LATE");
        var firstClaim = processingService.claimDue().getFirst();
        jdbcTemplate.update("UPDATE activation_request SET processing_started_at = now() - interval '10 minutes'");
        processingService.releaseExpiredClaims();
        makeEveryRequestDue();
        var secondClaim = processingService.claimDue().getFirst();

        processingService.recordResult(firstClaim, new ProviderResult.Success());

        var stored = reload(request);
        assertThat(stored.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(stored.getAttempts()).isEqualTo(secondClaim.attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("requests not yet due are left alone")
    void requestsNotDueAreNotClaimed() {
        pendingRequest("EXT-LATER");
        jdbcTemplate.update("UPDATE activation_request SET next_attempt_at = now() + interval '1 hour'");

        assertThat(processingService.claimDue()).isEmpty();
    }

    private ActivationRequest pendingRequest(String externalId) {
        return repository.save(ActivationRequest.create(externalId, "CUST-1", "INTERNET-500", "client-a",
                "trace-" + externalId, Instant.now().minus(Duration.ofSeconds(1))));
    }

    /**
     * Calls for this request only: a delayed call from a previous test (e.g. the timeout scenario) can
     * reach WireMock's journal after the reset between tests.
     */
    private static RequestPatternBuilder callsFor(ActivationRequest request) {
        return postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString()));
    }

    private ActivationRequest reload(ActivationRequest request) {
        return repository.findById(request.getId()).orElseThrow();
    }

    private void makeEveryRequestDue() {
        jdbcTemplate.update("UPDATE activation_request SET next_attempt_at = now() - interval '1 second'"
                + " WHERE status = 'PENDING'");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other worker");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
