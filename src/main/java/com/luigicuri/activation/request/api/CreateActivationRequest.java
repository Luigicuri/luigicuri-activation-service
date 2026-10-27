package com.luigicuri.activation.request.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.luigicuri.activation.request.application.CreateActivationRequestCommand;

/**
 * Body of {@code POST /api/v1/requests}. Identifiers are 1-64 characters of letters, digits,
 * {@code .}, {@code _} or {@code -} (R1). Messages are explicit so they do not depend on the JVM locale.
 */
public record CreateActivationRequest(
        @NotBlank(message = "is required")
        @Size(max = 64, message = "must have at most 64 characters")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String externalId,

        @NotBlank(message = "is required")
        @Size(max = 64, message = "must have at most 64 characters")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String customerId,

        @NotBlank(message = "is required")
        @Size(max = 64, message = "must have at most 64 characters")
        @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";
    static final String IDENTIFIER_MESSAGE = "must contain only letters, digits, '.', '_' or '-'";

    CreateActivationRequestCommand toCommand() {
        return new CreateActivationRequestCommand(externalId, customerId, productCode);
    }
}
