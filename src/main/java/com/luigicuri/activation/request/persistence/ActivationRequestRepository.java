package com.luigicuri.activation.request.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.luigicuri.activation.request.domain.ActivationRequest;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    /** Owner-scoped lookup: another client's request is indistinguishable from a missing one (R8.4). */
    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);
}
