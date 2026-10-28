package com.luigicuri.activation.request.processing;

import java.util.UUID;

import com.luigicuri.activation.provider.ActivationCommand;
import com.luigicuri.activation.request.domain.ActivationRequest;

/**
 * Snapshot of a request claimed by this worker, used outside the claiming transaction.
 * {@code attempt} identifies the claim: a result is only recorded if the request is still on it.
 */
public record ClaimedActivation(UUID id, int attempt, String customerId, String productCode, String correlationId) {

    static ClaimedActivation of(ActivationRequest request) {
        return new ClaimedActivation(request.getId(), request.getAttempts(), request.getCustomerId(),
                request.getProductCode(), request.getCorrelationId());
    }

    ActivationCommand toCommand() {
        return new ActivationCommand(id, customerId, productCode, correlationId);
    }
}
