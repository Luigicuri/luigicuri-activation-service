package com.luigicuri.activation.shared.security;

import static com.luigicuri.activation.support.TestTokens.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.request.api.ActivationRequestController;
import com.luigicuri.activation.request.application.ActivationRequestService;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/** R8.5: each client system has its own call budget; going over it is a 429 with Retry-After. */
@WebMvcTest(controllers = ActivationRequestController.class, properties = {
        "activation.rate-limit.limit-for-period=2", "activation.rate-limit.refresh-period=1m"})
@Import({SecurityConfiguration.class, CurrentClient.class})
class RateLimitTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RateLimiterRegistry rateLimiters;

    @MockitoBean
    ActivationRequestService service;

    @Test
    @DisplayName("over the limit -> 429 with Retry-After; another client keeps its own budget")
    void clientOverItsLimitIsThrottledWithoutAffectingOthers() throws Exception {
        given(service.list(any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/requests").with(client("busy-system"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/requests").with(client("busy-system"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/requests").with(client("busy-system")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/too-many-requests"))
                .andExpect(jsonPath("$.detail").value(
                        "This client exceeded 2 requests per 1m. Try again after the Retry-After seconds."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        mockMvc.perform(get("/api/v1/requests").with(client("quiet-system"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the limit applies after authentication: an anonymous call is a 401, never a 429")
    void unauthenticatedCallsAreRejectedBeforeTheLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/requests")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void onlyTheApiIsLimited() {
        var filter = new ClientRateLimitFilter(rateLimiters, new CurrentClient(), new ObjectMapper(),
                new RateLimitProperties(1, Duration.ofSeconds(1)));

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/v3/api-docs"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/requests"))).isFalse();
    }
}
