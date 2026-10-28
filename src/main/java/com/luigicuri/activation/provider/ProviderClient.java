package com.luigicuri.activation.provider;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.luigicuri.activation.shared.correlation.CorrelationId;

/**
 * Calls {@code POST /provider/v1/activate}. Never throws for provider-side problems: every outcome,
 * including timeouts and connection errors, is returned as a {@link ProviderResult}.
 */
@Component
public class ProviderClient {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient restClient;
    private final Clock clock;

    public ProviderClient(RestClient providerRestClient, Clock clock) {
        this.restClient = providerRestClient;
        this.clock = clock;
    }

    /**
     * The request id is sent as {@code Idempotency-Key}: after a timeout the provider may already have
     * activated the product, and the retry must not activate it twice (R5.5).
     */
    public ProviderResult activate(ActivationCommand command) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY, command.requestId().toString())
                    .headers(headers -> {
                        if (command.correlationId() != null) {
                            headers.set(CorrelationId.HEADER, command.correlationId());
                        }
                    })
                    .body(new ActivateRequestBody(command.requestId(), command.customerId(), command.productCode()))
                    .exchange((request, response) ->
                            ProviderResponses.classify(response.getStatusCode(), response.getHeaders(), clock.instant()));
        } catch (ResourceAccessException e) {
            var reason = isTimeout(e) ? "Provider call timed out" : "Provider is unreachable";
            return new ProviderResult.TransientFailure(reason, Optional.empty());
        }
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // The JDK client reports both connect and read timeouts as HttpTimeoutException
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    record ActivateRequestBody(UUID requestId, String customerId, String productCode) {
    }
}
