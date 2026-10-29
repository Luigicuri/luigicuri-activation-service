package com.luigicuri.activation.request.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;

/** R9.3: queue size per status, read from the database on every scrape. */
class ActivationQueueMetricsTest {

    @Test
    void reportsPendingAndProcessingCountsOnEachRead() {
        var repository = mock(ActivationRequestRepository.class);
        given(repository.countByStatus(ActivationStatus.PENDING)).willReturn(7L, 3L);
        given(repository.countByStatus(ActivationStatus.PROCESSING)).willReturn(2L);
        var registry = new SimpleMeterRegistry();

        new ActivationQueueMetrics(repository).bindTo(registry);

        var pending = registry.get(ActivationQueueMetrics.NAME).tag("status", "PENDING").gauge();
        assertThat(pending.value()).isEqualTo(7);
        assertThat(pending.value()).as("not cached between scrapes").isEqualTo(3);
        assertThat(registry.get(ActivationQueueMetrics.NAME).tag("status", "PROCESSING").gauge().value())
                .isEqualTo(2);
    }
}
