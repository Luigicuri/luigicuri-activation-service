package com.luigicuri.activation.request.domain;

public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Cannot transition activation request from %s to %s".formatted(from, to));
    }
}
