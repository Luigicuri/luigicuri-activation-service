package com.luigicuri.activation.request.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;
import com.luigicuri.activation.shared.tracing.TraceContexts;

/** R4: the decision taken after the unique constraint fires. Concurrency itself is covered by an IT. */
@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final String CLIENT = "client-a";
    private static final CreateActivationRequestCommand COMMAND =
            new CreateActivationRequestCommand("EXT-1", "CUST-1", "INTERNET-500");

    @Mock
    ActivationRequestRepository repository;

    @Mock
    TraceContexts traceContexts;

    ActivationRequestService service;

    @BeforeEach
    void setUp() {
        service = new ActivationRequestService(repository, traceContexts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void newExternalIdIsCreatedAsPending() {
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(COMMAND, CLIENT);

        assertThat(result).isInstanceOf(CreationResult.Created.class);
        assertThat(result.request().getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(result.request().getClientId()).isEqualTo(CLIENT);
        assertThat(result.request().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("R9.2 the creating request's trace is stored, so the worker can continue it")
    void currentTraceIsStoredWithTheRequest() {
        var traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        given(traceContexts.currentTraceParent()).willReturn(traceParent);
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(COMMAND, CLIENT);

        assertThat(result.request().getTraceParent()).isEqualTo(traceParent);
    }

    @Test
    @DisplayName("R4.2 same externalId and same content returns the existing request")
    void retryWithSameContentReturnsExisting() {
        var existing = existing("CUST-1", "INTERNET-500", CLIENT);
        givenExternalIdTakenBy(existing);

        var result = service.create(COMMAND, CLIENT);

        assertThat(result).isInstanceOf(CreationResult.AlreadyExists.class);
        assertThat(result.request()).isSameAs(existing);
    }

    @Test
    @DisplayName("R4.3 same externalId with different content is a conflict")
    void sameExternalIdWithDifferentContentIsRejected() {
        givenExternalIdTakenBy(existing("CUST-1", "TV-100", CLIENT));

        assertThatThrownBy(() -> service.create(COMMAND, CLIENT))
                .isInstanceOf(DuplicateActivationRequestException.class)
                .hasMessageContaining("different customerId or productCode");
    }

    @Test
    @DisplayName("R4.5 externalId owned by another client is a conflict that reveals nothing")
    void externalIdOwnedByAnotherClientIsRejectedWithoutDetails() {
        givenExternalIdTakenBy(existing("CUST-1", "INTERNET-500", "client-b"));

        assertThatThrownBy(() -> service.create(COMMAND, CLIENT))
                .isInstanceOf(DuplicateActivationRequestException.class)
                .hasMessage("The externalId is already in use.");
    }

    @Test
    void otherIntegrityViolationsAreNotTreatedAsDuplicates() {
        given(repository.saveAndFlush(any())).willThrow(violationOf("ck_activation_request_status"));

        assertThatThrownBy(() -> service.create(COMMAND, CLIENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(repository, never()).findByExternalId(any());
    }

    @Test
    void duplicateWhoseRowCannotBeReadAgainIsAnError() {
        given(repository.saveAndFlush(any()))
                .willThrow(violationOf(ActivationRequestService.EXTERNAL_ID_CONSTRAINT));
        given(repository.findByExternalId("EXT-1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(COMMAND, CLIENT)).isInstanceOf(IllegalStateException.class);
    }

    private void givenExternalIdTakenBy(ActivationRequest existing) {
        given(repository.saveAndFlush(any()))
                .willThrow(violationOf(ActivationRequestService.EXTERNAL_ID_CONSTRAINT));
        given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(existing));
    }

    private static ActivationRequest existing(String customerId, String productCode, String clientId) {
        return ActivationRequest.create("EXT-1", customerId, productCode, clientId, null, null, NOW.minusSeconds(5));
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        var cause = new ConstraintViolationException("violation", new SQLException("duplicate key"), constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
