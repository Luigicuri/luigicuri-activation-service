package com.luigicuri.activation.request.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    /** Size of the queue per status, for the metrics (R9.3). */
    long countByStatus(ActivationStatus status);

    /** Owner-scoped lookup: another client's request is indistinguishable from a missing one (R8.4). */
    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    /**
     * Locks a batch of due PENDING requests. SKIP LOCKED makes concurrent workers (other threads or
     * instances) skip rows already locked instead of waiting, so each one gets a different batch (R5.8).
     * Must run inside a transaction; the locks are released on commit.
     */
    @Query(value = """
            SELECT * FROM activation_request
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

    /** Locks PROCESSING requests whose lease expired: the instance handling them most likely died (R5.7). */
    @Query(value = """
            SELECT * FROM activation_request
            WHERE status = 'PROCESSING' AND processing_started_at < :startedBefore
            ORDER BY processing_started_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockExpiredProcessing(@Param("startedBefore") Instant startedBefore,
                                                  @Param("limit") int limit);
}
