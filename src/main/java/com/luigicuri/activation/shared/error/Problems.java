package com.luigicuri.activation.shared.error;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.correlation.CorrelationId;

/** Builds the RFC 9457 problems used across the API, so every error has the same shape. */
public final class Problems {

    public static final String BASE_URI = "https://api.luigicuri.dev/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE_URI + slug));
        problem.setTitle(title);
        return identified(problem);
    }

    /**
     * Adds what finds the error in the logs and in the tracing UI: the correlationId always, the traceId
     * whenever the request is being traced.
     */
    public static ProblemDetail identified(ProblemDetail problem) {
        problem.setProperty("correlationId", CorrelationId.current());
        var traceId = CorrelationId.currentTraceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    /** For errors raised outside Spring MVC (servlet filters, security), where no exception handler applies. */
    public static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem,
                             ObjectMapper objectMapper) throws IOException {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
