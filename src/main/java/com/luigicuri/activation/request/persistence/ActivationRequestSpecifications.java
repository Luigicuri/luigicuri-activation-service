package com.luigicuri.activation.request.persistence;

import org.springframework.data.jpa.domain.Specification;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;

/**
 * Filters for the listing. Only the filters actually supplied become SQL predicates, which keeps the
 * statements simple enough for PostgreSQL to use the matching (client_id, ..., created_at) index.
 */
public final class ActivationRequestSpecifications {

    private ActivationRequestSpecifications() {
    }

    public static Specification<ActivationRequest> ownedBy(String clientId) {
        return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
    }

    public static Specification<ActivationRequest> hasStatus(ActivationStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ActivationRequest> hasCustomerId(String customerId) {
        return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }
}
