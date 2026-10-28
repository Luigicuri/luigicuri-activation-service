package com.luigicuri.activation.request.processing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.luigicuri.activation.support.TestTokens.client;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.luigicuri.activation.support.TestcontainersConfiguration;

/**
 * The whole flow with the real scheduler: POST returns PENDING immediately, the worker picks the
 * request up on its own and the correlation id of the POST reaches the provider (R1.5, R5.1, R9.2).
 */
@SpringBootTest(properties = {"activation.processing.enabled=true", "activation.processing.poll-interval=100ms"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ScheduledProcessingIT {

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

    @Test
    void createdRequestIsActivatedInTheBackground() throws Exception {
        provider.stubFor(post("/provider/v1/activate").willReturn(aResponse().withStatus(200)));

        var created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(client("client-a"))
                        .header("X-Correlation-Id", "e2e-trace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId": "EXT-E2E", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("client-a")))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));

        provider.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(id))
                .withHeader("X-Correlation-Id", equalTo("e2e-trace-1")));
    }
}
