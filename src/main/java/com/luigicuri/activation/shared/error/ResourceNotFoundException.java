package com.luigicuri.activation.shared.error;

/** Base for "this resource does not exist (for you)" errors, mapped to 404 by {@link ApiExceptionHandler}. */
public abstract class ResourceNotFoundException extends RuntimeException {

    protected ResourceNotFoundException(String message) {
        super(message);
    }
}
