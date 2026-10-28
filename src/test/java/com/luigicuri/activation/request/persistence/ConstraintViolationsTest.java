package com.luigicuri.activation.request.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** A duplicate is only recognised by the name of the unique constraint, never by the exception type alone. */
class ConstraintViolationsTest {

    private static final String UNIQUE = "uk_activation_request_external_id";

    @Test
    void recognisesTheNamedConstraintAnywhereInTheCauseChain() {
        var hibernate = new ConstraintViolationException("duplicate", new SQLException(), UNIQUE.toUpperCase());
        var wrapped = new DataIntegrityViolationException("outer", new RuntimeException("middle", hibernate));

        assertThat(ConstraintViolations.isViolationOf(wrapped, UNIQUE)).isTrue();
    }

    @Test
    void otherConstraintIsNotTheDuplicate() {
        var hibernate = new ConstraintViolationException("check", new SQLException(), "ck_activation_request_status");

        assertThat(ConstraintViolations.isViolationOf(new DataIntegrityViolationException("x", hibernate), UNIQUE))
                .isFalse();
    }

    @Test
    void integrityErrorWithoutConstraintInformationIsNotTheDuplicate() {
        assertThat(ConstraintViolations.isViolationOf(new DataIntegrityViolationException("no cause"), UNIQUE))
                .isFalse();
    }
}
