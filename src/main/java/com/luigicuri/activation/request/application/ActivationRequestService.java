package com.luigicuri.activation.request.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CreationResult create(CreateActivationRequestCommand command, String clientId) {
        var request = ActivationRequest.create(command.externalId(), command.customerId(),
                command.productCode(), clientId, null, clock.instant());
        return new CreationResult.Created(repository.saveAndFlush(request));
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }
}
