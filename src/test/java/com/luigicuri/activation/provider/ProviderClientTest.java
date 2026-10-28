package com.luigicuri.activation.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/** The HTTP contract with the provider, against a WireMock server. No Spring context needed. */
class ProviderClientTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID requestId = UUID.randomUUID();
    private final ActivationCommand command = new ActivationCommand(requestId, "CUST-1", "INTERNET-500", "trace-123");

    private static ProviderClient clientFor(String baseUrl) {
        var properties = new ProviderProperties(baseUrl, "secret-key", Duration.ofMillis(500), READ_TIMEOUT);
        RestClient restClient = new ProviderClientConfiguration().providerRestClient(RestClient.builder(), properties);
        return new ProviderClient(restClient, Clock.systemUTC());
    }

    private ProviderClient client() {
        return clientFor(provider.baseUrl());
    }

    @Test
    void sendsIdempotencyKeyCorrelationIdCredentialAndBody() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));

        var result = client().activate(command);

        assertThat(result).isInstanceOf(ProviderResult.Success.class);
        provider.verify(postRequestedFor(urlEqualTo(ProviderClient.ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(requestId.toString()))
                .withHeader("X-Correlation-Id", equalTo("trace-123"))
                .withHeader("Authorization", equalTo("Bearer secret-key"))
                .withRequestBody(equalToJson("""
                        {"requestId": "%s", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                        """.formatted(requestId))));
    }

    @Test
    void withoutCorrelationIdOrCredentialThoseHeadersAreNotSent() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
        var properties = new ProviderProperties(provider.baseUrl(), " ", Duration.ofMillis(500), READ_TIMEOUT);
        var client = new ProviderClient(
                new ProviderClientConfiguration().providerRestClient(RestClient.builder(), properties), Clock.systemUTC());

        client.activate(new ActivationCommand(requestId, "CUST-1", "INTERNET-500", null));

        provider.verify(postRequestedFor(urlEqualTo(ProviderClient.ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(requestId.toString()))
                .withoutHeader("X-Correlation-Id")
                .withoutHeader("Authorization"));
    }

    @Test
    void badRequestIsPermanent() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(400)));

        assertThat(client().activate(command)).isInstanceOf(ProviderResult.PermanentFailure.class);
    }

    @Test
    void serverErrorIsTransient() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(500)));

        assertThat(client().activate(command)).isInstanceOf(ProviderResult.TransientFailure.class);
    }

    @Test
    void serviceUnavailableCarriesRetryAfter() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));

        assertThat(client().activate(command)).isEqualTo(
                new ProviderResult.TransientFailure("Provider returned HTTP 503", Optional.of(Duration.ofSeconds(30))));
    }

    @Test
    void responseSlowerThanReadTimeoutIsATransientTimeout() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(200).withFixedDelay((int) READ_TIMEOUT.toMillis() * 5 / 2)));

        assertThat(client().activate(command)).isEqualTo(
                new ProviderResult.TransientFailure("Provider call timed out", Optional.empty()));
    }

    @Test
    void unreachableProviderIsTransient() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        var result = clientFor("http://localhost:" + closedPort).activate(command);

        assertThat(result).isEqualTo(new ProviderResult.TransientFailure("Provider is unreachable", Optional.empty()));
    }
}
