package com.luigicuri.activation.request.processing;

import java.util.List;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;

/**
 * Requests currently waiting or in flight, per status (R9.3). A growing PENDING count means the provider
 * is failing, slow or refusing our credentials; PROCESSING that does not drain means abandoned work.
 * Counted on each scrape: both statuses are covered by small partial indexes, so the query stays cheap
 * however many finished requests the table holds.
 */
@Component
public class ActivationQueueMetrics implements MeterBinder {

    static final String NAME = "activation.requests.active";

    private final ActivationRequestRepository repository;

    public ActivationQueueMetrics(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : List.of(ActivationStatus.PENDING, ActivationStatus.PROCESSING)) {
            Gauge.builder(NAME, repository, r -> r.countByStatus(status))
                    .tag("status", status.name())
                    .description("Activation requests currently in this status")
                    .register(registry);
        }
    }
}
