package com.luigicuri.activation.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class ProblemsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesProblemJsonWithTheRequestPathAsInstance() throws Exception {
        var response = new MockHttpServletResponse();

        Problems.write(new MockHttpServletRequest("GET", "/api/v1/requests"), response,
                Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "A token is required."),
                objectMapper);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"type\":\"https://api.luigicuri.dev/problems/unauthorized\"")
                .contains("\"instance\":\"/api/v1/requests\"");
    }

    @Test
    void keepsAnInstanceThatWasAlreadySet() throws Exception {
        var problem = Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request", "Bad.");
        problem.setInstance(URI.create("/original"));
        var response = new MockHttpServletResponse();

        Problems.write(new MockHttpServletRequest("GET", "/other"), response, problem, objectMapper);

        assertThat(response.getContentAsString()).contains("\"instance\":\"/original\"");
    }
}
