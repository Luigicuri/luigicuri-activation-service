package com.luigicuri.activation.shared.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.error.Problems;

/**
 * 401 and 403 in the same problem+json format as every other error (R7.1). The standard bearer token
 * handlers still set the {@code WWW-Authenticate} header; the reason from the exception is not exposed.
 *
 * <p>As the bearer token filter's failure handler it also tells two cases apart: a token that is invalid
 * (401) and a token that could not be checked at all because the issuer's keys are unreachable (503, R7.5).
 * Left alone, the filter rethrows the latter and the servlet container turns it into an error page.
 */
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    SecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, exception);
        Problems.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                "Unauthorized", "A valid bearer token is required."), objectMapper);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof AuthenticationServiceException) {
            log.error("Bearer token could not be validated: the issuer's keys are unreachable", exception);
            response.setHeader(HttpHeaders.RETRY_AFTER, Problems.RETRY_AFTER_SECONDS);
            Problems.write(request, response, Problems.serviceUnavailable(), objectMapper);
            return;
        }
        commence(request, response, exception);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        bearerAccessDeniedHandler.handle(request, response, exception);
        Problems.write(request, response, Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "The token does not grant the scope required for this operation."), objectMapper);
    }
}
