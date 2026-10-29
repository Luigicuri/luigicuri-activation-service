package com.luigicuri.activation.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;

import com.luigicuri.activation.shared.web.RequestBodySizeLimitFilter;

/** Mappings that the web slice tests cannot trigger through MockMvc. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ServletWebRequest webRequest =
            new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/requests"), new MockHttpServletResponse());

    @Test
    void chunkedBodyCutOffWhileReadingIsA413() {
        var cause = new RequestBodySizeLimitFilter.PayloadTooLargeException();
        var exception = new HttpMessageNotReadableException("I/O error", cause, new MockHttpInputMessage(new byte[0]));

        var response = handler.handleHttpMessageNotReadable(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST,
                webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(((ProblemDetail) response.getBody()).getType().toString()).endsWith("/payload-too-large");
    }

    @Test
    void typeMismatchOutsideAControllerParameterIsA400NamingTheProperty() {
        var exception = new TypeMismatchException("abc", Integer.class);
        exception.initPropertyName("size");

        var response = handler.handleTypeMismatch(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) response.getBody()).getProperties())
                .containsEntry("errors", List.of(new FieldViolation("size", "has an invalid value")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseUnavailable")
    @DisplayName("R7.5 database unreachable -> 503 with Retry-After, no internal details")
    void databaseOutageIsA503WithRetryAfter(String description, Exception exception) {
        var response = handler.handleDatabaseUnavailable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        var problem = (ProblemDetail) response.getBody();
        assertThat(problem.getType().toString()).endsWith("/service-unavailable");
        assertThat(problem.getDetail()).doesNotContain("JDBC", "Connection is not available");
    }

    static Stream<Arguments> databaseUnavailable() {
        var poolTimeout = new SQLTransientConnectionException("HikariPool-1 - Connection is not available");
        return Stream.of(
                Arguments.of("no connection to open a transaction",
                        new CannotCreateTransactionException("Could not open JPA EntityManager", poolTimeout)),
                Arguments.of("connection lost during data access",
                        new DataAccessResourceFailureException("Unable to acquire JDBC Connection", poolTimeout)));
    }

    @Test
    void responseWithoutBodyIsLeftUntouched() {
        var response = handler.handleExceptionInternal(new IllegalStateException(), null, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void nothingIsWrittenWhenTheResponseIsAlreadyCommitted() {
        var committed = new MockHttpServletResponse();
        committed.setCommitted(true);
        var request = new ServletWebRequest(new MockHttpServletRequest(), committed);

        var response = handler.handleExceptionInternal(new IllegalStateException(), null, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);

        assertThat(response).isNull();
    }
}
