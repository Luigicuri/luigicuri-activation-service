package com.luigicuri.activation.request.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** R5.6: backoff, jitter, Retry-After and the attempt limit. */
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final RetryProperties PROPERTIES =
            new RetryProperties(5, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1));

    /** random = 1.0 exposes the upper bound of the jitter window, i.e. the raw exponential delay. */
    private static final RetryPolicy NO_JITTER = new RetryPolicy(PROPERTIES, () -> 1.0);

    @ParameterizedTest(name = "after attempt {0} wait up to {1}s")
    @CsvSource({"1, 2", "2, 4", "3, 8", "4, 16"})
    void delayGrowsExponentially(int attemptsMade, long expectedSeconds) {
        assertThat(NO_JITTER.nextAttemptAt(attemptsMade, Optional.empty(), NOW))
                .contains(NOW.plusSeconds(expectedSeconds));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        var policy = new RetryPolicy(new RetryProperties(20, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1)),
                () -> 1.0);

        assertThat(policy.nextAttemptAt(10, Optional.empty(), NOW)).contains(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void jitterPicksAPointInsideTheWindow() {
        var policy = new RetryPolicy(PROPERTIES, () -> 0.25);

        // third attempt: window is [0, 8s] -> 25% of it
        assertThat(policy.nextAttemptAt(3, Optional.empty(), NOW)).contains(NOW.plusSeconds(2));
    }

    @Test
    void retryAfterReplacesTheComputedDelay() {
        assertThat(NO_JITTER.nextAttemptAt(1, Optional.of(Duration.ofSeconds(30)), NOW))
                .contains(NOW.plusSeconds(30));
    }

    @Test
    void retryAfterIsCappedAtMaxDelay() {
        assertThat(NO_JITTER.nextAttemptAt(1, Optional.of(Duration.ofHours(2)), NOW))
                .contains(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void noMoreAttemptsOnceTheLimitIsReached() {
        assertThat(NO_JITTER.nextAttemptAt(5, Optional.empty(), NOW)).isEmpty();
        assertThat(NO_JITTER.nextAttemptAt(5, Optional.of(Duration.ofSeconds(1)), NOW)).isEmpty();
    }
}
