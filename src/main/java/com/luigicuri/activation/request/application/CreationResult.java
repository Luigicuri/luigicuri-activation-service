package com.luigicuri.activation.request.application;

import com.luigicuri.activation.request.domain.ActivationRequest;

/** Outcome of a create call: a new request, or the existing one when the source system retried. */
public sealed interface CreationResult {

    ActivationRequest request();

    record Created(ActivationRequest request) implements CreationResult {
    }

    record AlreadyExists(ActivationRequest request) implements CreationResult {
    }
}
