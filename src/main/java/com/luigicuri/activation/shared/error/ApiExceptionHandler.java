package com.luigicuri.activation.shared.error;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.luigicuri.activation.request.application.ActivationRequestNotFoundException;
import com.luigicuri.activation.shared.correlation.CorrelationId;

/**
 * Maps every error to {@code application/problem+json} (RFC 9457) with a {@code traceId} that
 * matches the logs. Internal details (stack traces, SQL, class names) never reach the client (R7).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String PROBLEM_BASE_URI = "https://api.luigicuri.dev/problems/";

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ActivationRequestNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "An unexpected error occurred. Report the traceId to get it investigated."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A malformed path variable means the resource cannot exist (404); a malformed query parameter is a 400. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch
                && mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
            var notFound = problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found",
                    "No resource exists for the given identifier.");
            return handleExceptionInternal(ex, notFound, headers, HttpStatus.NOT_FOUND, request);
        }
        var violation = new FieldViolation(ex.getPropertyName(), "has an invalid value");
        return handleExceptionInternal(ex, validationProblem(List.of(violation)), headers,
                HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var malformed = problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request body is missing or is not valid JSON.");
        return handleExceptionInternal(ex, malformed, headers, status, request);
    }

    /** Every response produced by the base class (405, 415, 406...) also gets the traceId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        var response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            problemDetail.setProperty("traceId", CorrelationId.current());
        }
        return response;
    }

    static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE_URI + slug));
        problem.setTitle(title);
        problem.setProperty("traceId", CorrelationId.current());
        return problem;
    }

    private static ProblemDetail validationProblem(List<FieldViolation> violations) {
        var problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Invalid data",
                "One or more fields failed validation.");
        problem.setProperty("errors", violations);
        return problem;
    }

    private static ResponseEntity<Object> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
