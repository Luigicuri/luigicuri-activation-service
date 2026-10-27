package com.luigicuri.activation.request.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * Whether the failure was caused by the given constraint. Checking the name matters: a NOT NULL
     * or CHECK violation must surface as an error, not be mistaken for a duplicate.
     */
    public static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
