package com.luigicuri.activation.request.application;

import static com.luigicuri.activation.request.persistence.ActivationRequestSpecifications.hasCustomerId;
import static com.luigicuri.activation.request.persistence.ActivationRequestSpecifications.hasStatus;
import static com.luigicuri.activation.request.persistence.ActivationRequestSpecifications.ownedBy;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;
import com.luigicuri.activation.request.persistence.ConstraintViolations;
import com.luigicuri.activation.shared.correlation.CorrelationId;
import com.luigicuri.activation.shared.tracing.TraceContexts;

@Service
public class ActivationRequestService {

    static final String EXTERNAL_ID_CONSTRAINT = "uk_activation_request_external_id";

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final TraceContexts traceContexts;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, TraceContexts traceContexts,
                                    Clock clock) {
        this.repository = repository;
        this.traceContexts = traceContexts;
        this.clock = clock;
    }

    /**
     * Inserts first and lets the unique constraint decide (ADR-0002). Checking for the externalId
     * before inserting is not enough: two concurrent transactions would both see nothing and both
     * insert. Only the database sees both at once.
     *
     * <p>Deliberately not {@code @Transactional}: the failed insert marks its transaction as
     * rollback-only, so the existing request has to be read in a separate one.
     */
    public CreationResult create(CreateActivationRequestCommand command, String clientId) {
        var request = ActivationRequest.create(command.externalId(), command.customerId(),
                command.productCode(), clientId, CorrelationId.current(), traceContexts.currentTraceParent(),
                clock.instant());
        try {
            var created = repository.saveAndFlush(request);
            // First log line of the request's life; the worker logs the rest under the same traceId (R9.2).
            log.info("Activation request {} created for externalId {}", created.getId(), created.getExternalId());
            return new CreationResult.Created(created);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isViolationOf(e, EXTERNAL_ID_CONSTRAINT)) {
                throw e;
            }
            return resolveDuplicate(command, clientId);
        }
    }

    private CreationResult resolveDuplicate(CreateActivationRequestCommand command, String clientId) {
        var existing = repository.findByExternalId(command.externalId())
                .orElseThrow(() -> new IllegalStateException(
                        "Unique constraint violated but no request found for the externalId"));

        if (!existing.isOwnedBy(clientId)) {
            // Says nothing about the other client's request (R4.5, BOLA).
            throw new DuplicateActivationRequestException("The externalId is already in use.");
        }
        if (!existing.hasSameContentAs(command.customerId(), command.productCode())) {
            throw new DuplicateActivationRequestException(
                    "The externalId '%s' was already used with a different customerId or productCode."
                            .formatted(command.externalId()));
        }
        log.info("Retry of externalId {} answered with existing activation request {}",
                command.externalId(), existing.getId());
        return new CreationResult.AlreadyExists(existing);
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    /** Newest first; id breaks ties so pages stay stable when timestamps collide. */
    @Transactional(readOnly = true)
    public Page<ActivationRequest> list(ActivationRequestQuery query, String clientId) {
        Specification<ActivationRequest> filter = ownedBy(clientId);
        if (query.status() != null) {
            filter = filter.and(hasStatus(query.status()));
        }
        if (query.customerId() != null) {
            filter = filter.and(hasCustomerId(query.customerId()));
        }
        var sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return repository.findAll(filter, PageRequest.of(query.page(), query.size(), sort));
    }
}
