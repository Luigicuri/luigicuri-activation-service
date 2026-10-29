package com.luigicuri.activation.request.processing;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.observation.ObservationPredicate;

@Configuration(proxyBeanMethods = false)
public class ProcessingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProcessingConfiguration.class);

    static final String SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution";

    @Bean
    RetryPolicy retryPolicy(RetryProperties properties) {
        return new RetryPolicy(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Opens on provider failures (5xx, timeouts, rejected credentials), not on requests it refuses (ADR-0003). */
    @Bean
    CircuitBreaker providerCircuitBreaker(CircuitBreakerProperties properties) {
        var circuitBreaker = CircuitBreaker.of("provider", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Provider circuit breaker {}", event.getStateTransition()));
        return circuitBreaker;
    }

    /**
     * Polling every second would otherwise start a trace per cycle, mostly empty. The worker opens its own
     * span per request instead, continuing the trace of the request that created it.
     */
    @Bean
    ObservationPredicate skipSchedulerPollingObservations() {
        return (name, context) -> !SCHEDULED_TASK_OBSERVATION.equals(name);
    }

    /** The worker can be switched off, e.g. to run an API-only instance or deterministic tests. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "activation.processing", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
