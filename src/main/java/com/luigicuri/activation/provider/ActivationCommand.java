package com.luigicuri.activation.provider;

import java.util.UUID;

/** What the provider needs to activate a product. Independent from the JPA entity on purpose. */
public record ActivationCommand(UUID requestId, String customerId, String productCode, String correlationId) {
}
