package com.luigicuri.activation.request.application;

public record CreateActivationRequestCommand(String externalId, String customerId, String productCode) {
}
