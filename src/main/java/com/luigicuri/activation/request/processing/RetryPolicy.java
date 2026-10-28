package com.luigicuri.activation.request.processing;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Decides when a transiently failed request is tried again (R5.6, ADR-0003).
 *
 * <p>Exponential backoff with "full jitter": the delay is a random value between zero and
 * {@code min(maxDelay, initialDelay * multiplier^(attempts - 1))}. Without jitter, every request that
 * failed during the same provider outage would retry at the same instant and overload it again.
 * A Retry-After sent by the provider replaces the computed delay, still capped by {@code maxDelay}.
 */
public class RetryPolicy {

    private final RetryProperties properties;
    private final DoubleSupplier random;

    /** @param random source of values in [0, 1); injectable so tests are deterministic */
    public RetryPolicy(RetryProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    /**
     * @param attemptsMade provider calls already made for the request, including the one that just failed
     * @return when to try again, or empty when the attempt limit is reached
     */
    public Optional<Instant> nextAttemptAt(int attemptsMade, Optional<Duration> retryAfter, Instant now) {
        if (attemptsMade >= properties.maxAttempts()) {
            return Optional.empty();
        }
        var delay = retryAfter
                .map(this::capped)
                .orElseGet(() -> jittered(backoff(attemptsMade)));
        return Optional.of(now.plus(delay));
    }

    private Duration backoff(int attemptsMade) {
        double factor = Math.pow(properties.multiplier(), Math.max(0, attemptsMade - 1));
        double millis = properties.initialDelay().toMillis() * factor;
        return capped(Duration.ofMillis((long) Math.min(millis, properties.maxDelay().toMillis())));
    }

    private Duration jittered(Duration ceiling) {
        return Duration.ofMillis((long) (ceiling.toMillis() * random.getAsDouble()));
    }

    private Duration capped(Duration delay) {
        return delay.compareTo(properties.maxDelay()) > 0 ? properties.maxDelay() : delay;
    }
}
