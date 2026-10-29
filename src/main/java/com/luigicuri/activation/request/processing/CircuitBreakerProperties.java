package com.luigicuri.activation.request.processing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit breaker around the provider (ADR-0003). Counts the last {@code slidingWindowSize} calls; once at
 * least {@code minimumNumberOfCalls} were made and {@code failureRateThreshold}% of them failed, the circuit
 * opens and the worker stops calling for {@code waitDurationInOpenState}. Then {@code permittedCallsInHalfOpenState}
 * trial calls decide whether it closes again.
 */
@ConfigurationProperties(prefix = "activation.circuit-breaker")
public record CircuitBreakerProperties(float failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
                                       Duration waitDurationInOpenState, int permittedCallsInHalfOpenState) {
}
