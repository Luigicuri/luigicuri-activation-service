package com.luigicuri.activation.request.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Activation request %s was not found".formatted(id));
    }
}
