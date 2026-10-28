package com.luigicuri.activation.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/** R5.3: one rule per provider response. */
class ProviderResponsesTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    private static ProviderResult classify(int status) {
        return classify(status, new HttpHeaders());
    }

    private static ProviderResult classify(int status, HttpHeaders headers) {
        return ProviderResponses.classify(HttpStatusCode.valueOf(status), headers, NOW);
    }

    @ParameterizedTest(name = "HTTP {0} -> success")
    @ValueSource(ints = {200, 201, 202, 204})
    void successStatusesComplete(int status) {
        assertThat(classify(status)).isInstanceOf(ProviderResult.Success.class);
    }

    @ParameterizedTest(name = "HTTP {0} -> permanent failure")
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void clientErrorsArePermanent(int status) {
        assertThat(classify(status)).isEqualTo(
                new ProviderResult.PermanentFailure("Provider rejected the request (HTTP " + status + ")"));
    }

    @ParameterizedTest(name = "HTTP {0} -> transient failure")
    @ValueSource(ints = {408, 500, 502, 504})
    void serverErrorsAndRequestTimeoutAreTransient(int status) {
        assertThat(classify(status)).isEqualTo(
                new ProviderResult.TransientFailure("Provider returned HTTP " + status, Optional.empty()));
    }

    @ParameterizedTest(name = "HTTP {0} honours Retry-After in seconds")
    @ValueSource(ints = {429, 503})
    void throttlingHonoursRetryAfterSeconds(int status) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "120");

        assertThat(classify(status, headers)).isEqualTo(
                new ProviderResult.TransientFailure("Provider returned HTTP " + status,
                        Optional.of(Duration.ofSeconds(120))));
    }

    @Test
    void retryAfterAsHttpDate() {
        assertThat(ProviderResponses.parseRetryAfter("Mon, 26 Oct 2026 14:01:30 GMT", NOW))
                .contains(Duration.ofSeconds(90));
    }

    @Test
    void retryAfterInThePastMeansNow() {
        assertThat(ProviderResponses.parseRetryAfter("Mon, 26 Oct 2026 13:00:00 GMT", NOW))
                .contains(Duration.ZERO);
    }

    @Test
    void missingOrInvalidRetryAfterIsIgnored() {
        assertThat(ProviderResponses.parseRetryAfter(null, NOW)).isEmpty();
        assertThat(ProviderResponses.parseRetryAfter("soon", NOW)).isEmpty();
        assertThat(ProviderResponses.parseRetryAfter("-5", NOW)).contains(Duration.ZERO);
    }
}
