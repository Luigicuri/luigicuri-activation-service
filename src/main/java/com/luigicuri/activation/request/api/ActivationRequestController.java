package com.luigicuri.activation.request.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.luigicuri.activation.request.application.ActivationRequestService;
import com.luigicuri.activation.request.application.CreationResult;
import com.luigicuri.activation.shared.security.CurrentClient;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;
    private final CurrentClient currentClient;

    public ActivationRequestController(ActivationRequestService service, CurrentClient currentClient) {
        this.service = service;
        this.currentClient = currentClient;
    }

    /** 201 for a new request; 200 with the existing one when the source system retries (ADR-0002). */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body) {
        var result = service.create(body.toCommand(), currentClient.id());
        var response = ActivationRequestResponse.from(result.request());

        return switch (result) {
            case CreationResult.Created created -> ResponseEntity
                    .created(ServletUriComponentsBuilder.fromCurrentRequest()
                            .path("/{id}")
                            .buildAndExpand(created.request().getId())
                            .toUri())
                    .body(response);
            case CreationResult.AlreadyExists ignored -> ResponseEntity.ok(response);
        };
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id, currentClient.id()));
    }
}
