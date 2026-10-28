package com.luigicuri.activation.request.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.luigicuri.activation.request.application.ActivationRequestQuery;
import com.luigicuri.activation.request.application.ActivationRequestService;
import com.luigicuri.activation.request.application.CreationResult;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.shared.security.CurrentClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/requests")
@Tag(name = "Activation requests", description = "Create activation requests and follow their status")
@ApiResponse(responseCode = "401", description = "Missing, expired or invalid token", content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Token lacks the scope this operation needs", content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ActivationRequestController {

    private final ActivationRequestService service;
    private final CurrentClient currentClient;

    public ActivationRequestController(ActivationRequestService service, CurrentClient currentClient) {
        this.service = service;
        this.currentClient = currentClient;
    }

    /** 201 for a new request; 200 with the existing one when the source system retries (ADR-0002). */
    @PostMapping
    @Operation(summary = "Create an activation request",
            description = "Stores the request as PENDING and returns immediately; the provider is called in the "
                    + "background. Sending the same externalId with the same content again is a safe retry. "
                    + "Requires scope activation:write.")
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new request")
    @ApiResponse(responseCode = "200", description = "Retry of an existing request with the same content")
    @ApiResponse(responseCode = "400", description = "Invalid or malformed body", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "externalId already used with different content or by another client",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
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
    @Operation(summary = "Get an activation request and its current status",
            description = "Requires scope activation:read. Requests created by another client are not visible.")
    @ApiResponse(responseCode = "200", description = "The request")
    @ApiResponse(responseCode = "404", description = "No request with this id for the calling client", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ActivationRequestResponse get(@PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id, currentClient.id()));
    }

    @GetMapping
    @Operation(summary = "List the caller's activation requests, newest first",
            description = "Optional filters by status and customer. Requires scope activation:read.")
    @ApiResponse(responseCode = "200", description = "A page of requests")
    @ApiResponse(responseCode = "400", description = "Invalid filter or page size", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<ActivationRequestResponse> list(
            @RequestParam(required = false) ActivationStatus status,
            @Parameter(description = "Customer identifier") @RequestParam(required = false) @Size(max = 64, message = "must have at most 64 characters")
            String customerId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be 0 or greater") int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100") int size) {
        var query = new ActivationRequestQuery(status, customerId, page, size);
        return PageResponse.from(service.list(query, currentClient.id()), ActivationRequestResponse::from);
    }
}
