package com.luigicuri.activation.request.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.observation.Observation;

class ProcessingConfigurationTest {

    private final ProcessingConfiguration configuration = new ProcessingConfiguration();

    @Test
    void schedulerPollingIsNotTracedButEverythingElseIs() {
        var predicate = configuration.skipSchedulerPollingObservations();

        assertThat(predicate.test(ProcessingConfiguration.SCHEDULED_TASK_OBSERVATION, new Observation.Context()))
                .isFalse();
        assertThat(predicate.test("http.client.requests", new Observation.Context())).isTrue();
    }

    @Test
    void circuitBreakerFollowsTheConfiguredThresholds() {
        var circuitBreaker = configuration.providerCircuitBreaker(
                new CircuitBreakerProperties(50, 10, 5, Duration.ofSeconds(30), 1));

        var config = circuitBreaker.getCircuitBreakerConfig();
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
