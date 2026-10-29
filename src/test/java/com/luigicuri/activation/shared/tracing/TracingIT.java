package com.luigicuri.activation.shared.tracing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.luigicuri.activation.support.TestTokens.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.luigicuri.activation.request.processing.ActivationWorker;
import com.luigicuri.activation.support.IntegrationTest;

/**
 * R9.2 with tracing switched on: the POST, the asynchronous processing and the provider call share one
 * distributed trace, and error responses carry its traceId.
 */
@IntegrationTest
@AutoConfigureObservability(metrics = false)
@AutoConfigureMockMvc
class TracingIT {

    private static final Pattern TRACE_PARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-01");

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", provider::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ActivationWorker worker;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE activation_request");
    }

    @Test
    @DisplayName("the provider call continues the trace of the POST that created the request")
    void providerCallBelongsToTheCreatingTrace() throws Exception {
        provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(200)));

        var created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(client("client-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId": "EXT-TRACE", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        var stored = jdbcTemplate.queryForObject(
                "SELECT trace_parent FROM activation_request WHERE id = ?::uuid", String.class, id);
        var matcher = TRACE_PARENT.matcher(stored);
        assertThat(matcher.matches()).as("stored traceparent %s", stored).isTrue();
        var traceId = matcher.group(1);

        worker.processDueRequests();

        provider.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", matching(id))
                .withHeader("traceparent", matching("00-" + traceId + "-[0-9a-f]{16}-01")));
    }

    @Test
    @DisplayName("errors carry the traceId of the request, besides the correlationId")
    void errorResponseCarriesTheTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/requests/00000000-0000-0000-0000-000000000000").with(client("client-a"))
                        .header("X-Correlation-Id", "trace-it-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").value("trace-it-1"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
    }
}
