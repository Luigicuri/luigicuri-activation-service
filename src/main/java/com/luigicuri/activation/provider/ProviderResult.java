package com.luigicuri.activation.provider;

import java.time.Duration;
import java.util.Optional;

/** Outcome of a call to the provider, already classified by what the caller should do next (ADR-0003). */
public sealed interface ProviderResult {

    /** The product was activated. */
    record Success() implements ProviderResult {
    }

    /** The provider rejected the request itself; sending it again cannot succeed. */
    record PermanentFailure(String reason) implements ProviderResult {
    }

    /** Failure that may go away. {@code retryAfter} is set when the provider says when to come back. */
    record TransientFailure(String reason, Optional<Duration> retryAfter) implements ProviderResult {
    }
}
