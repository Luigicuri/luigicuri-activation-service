package com.luigicuri.activation.request.application;

import com.luigicuri.activation.request.domain.ActivationStatus;

/** Listing filters; {@code status} and {@code customerId} are optional (null means "any"). */
public record ActivationRequestQuery(ActivationStatus status, String customerId, int page, int size) {
}
