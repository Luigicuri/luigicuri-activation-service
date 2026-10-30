package com.luigicuri.activation.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.fasterxml.jackson.databind.ObjectMapper;

/** R7.5: a token we cannot check is our problem (503); a token that fails the checks is the caller's (401). */
class SecurityProblemHandlerTest {

    private final SecurityProblemHandler handler = new SecurityProblemHandler(new ObjectMapper());
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/requests");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void unreachableIssuerKeysAreA503WithRetryAfter() throws Exception {
        handler.onAuthenticationFailure(request, response,
                new AuthenticationServiceException("Couldn't retrieve remote JWK set"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString())
                .contains("/service-unavailable")
                .doesNotContain("JWK");
    }

    @Test
    void invalidTokenIsStillA401() throws Exception {
        handler.onAuthenticationFailure(request, response, new InvalidBearerTokenException("Jwt expired"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getContentAsString()).contains("/unauthorized").doesNotContain("expired");
    }
}
