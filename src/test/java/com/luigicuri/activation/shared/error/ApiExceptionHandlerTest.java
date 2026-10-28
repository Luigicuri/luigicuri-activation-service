package com.luigicuri.activation.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
