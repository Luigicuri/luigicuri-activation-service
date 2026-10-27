package com.luigicuri.activation.request.domain;

import static com.luigicuri.activation.request.domain.ActivationStatus.COMPLETED;
import static com.luigicuri.activation.request.domain.ActivationStatus.FAILED;
import static com.luigicuri.activation.request.domain.ActivationStatus.PENDING;
import static com.luigicuri.activation.request.domain.ActivationStatus.PROCESSING;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A request to activate a product for a customer.
 *
 * <p>Status only changes through the transition methods below, which reject anything outside
 * {@code PENDING -> PROCESSING -> COMPLETED | FAILED} and {@code PROCESSING -> PENDING} (retry).
 * There are no setters on purpose.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    static final int FAILURE_REASON_MAX_LENGTH = 255;

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ActivationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivationRequest() {
        // required by JPA
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.clientId = Objects.requireNonNull(clientId);
        request.correlationId = correlationId;
        request.status = PENDING;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** Claims the request for a call to the provider. Every call counts as an attempt. */
    public void startProcessing(Instant now) {
        requireStatus(PENDING, PROCESSING);
        status = PROCESSING;
        attempts++;
        processingStartedAt = now;
        updatedAt = now;
    }

    public void complete(Instant now) {
        requireStatus(PROCESSING, COMPLETED);
        status = COMPLETED;
        processingStartedAt = null;
        updatedAt = now;
    }

    /** Transient failure: back to the queue, eligible again at {@code nextAttemptAt}. */
    public void scheduleRetry(Instant nextAttemptAt, Instant now) {
        requireStatus(PROCESSING, PENDING);
        status = PENDING;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        processingStartedAt = null;
        updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        requireStatus(PROCESSING, FAILED);
        status = FAILED;
        failureReason = truncate(reason);
        processingStartedAt = null;
        updatedAt = now;
    }

    /** A retry from the source system carries the same content; anything else is a conflicting reuse. */
    public boolean hasSameContentAs(String customerId, String productCode) {
        return this.customerId.equals(customerId) && this.productCode.equals(productCode);
    }

    public boolean isOwnedBy(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void requireStatus(ActivationStatus expected, ActivationStatus target) {
        if (status != expected) {
            throw new IllegalStatusTransitionException(status, target);
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAILURE_REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
