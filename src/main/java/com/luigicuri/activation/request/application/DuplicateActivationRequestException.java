package com.luigicuri.activation.request.application;

/** The externalId is already taken by a request that is not a retry of this one. */
public class DuplicateActivationRequestException extends RuntimeException {

    public DuplicateActivationRequestException(String message) {
        super(message);
    }
}
