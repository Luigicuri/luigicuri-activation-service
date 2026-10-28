package com.luigicuri.activation.request.application;

import java.util.UUID;

import com.luigicuri.activation.shared.error.ResourceNotFoundException;

public class ActivationRequestNotFoundException extends ResourceNotFoundException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Activation request %s was not found".formatted(id));
    }
}
