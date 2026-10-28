package com.luigicuri.activation.request.application;

import com.luigicuri.activation.shared.error.ConflictException;

/** The externalId is already taken by a request that is not a retry of this one. */
public class DuplicateActivationRequestException extends ConflictException {

    public DuplicateActivationRequestException(String message) {
        super(message);
    }
}
