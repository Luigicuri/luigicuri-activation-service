package com.luigicuri.activation.shared.error;

/** Base for requests that conflict with the current state, mapped to 409 by {@link ApiExceptionHandler}. */
public abstract class ConflictException extends RuntimeException {

    protected ConflictException(String message) {
        super(message);
    }
}
