package com.luigicuri.activation.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/** Maps a provider HTTP response to a {@link ProviderResult} (R5.3). */
final class ProviderResponses {

    private ProviderResponses() {
    }

    static ProviderResult classify(HttpStatusCode status, HttpHeaders headers, Instant now) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Success();
        }
        if (code == 429 || code == 503) {
            return new ProviderResult.TransientFailure("Provider returned HTTP " + code,
                    parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER), now));
        }
        if (code == 408 || status.is5xxServerError()) {
            return new ProviderResult.TransientFailure("Provider returned HTTP " + code, Optional.empty());
        }
        return new ProviderResult.PermanentFailure("Provider rejected the request (HTTP " + code + ")");
    }

    /** Retry-After is either delay-seconds or an HTTP-date (RFC 9110). Anything unparseable is ignored. */
    static Optional<Duration> parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        var trimmed = value.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException notSeconds) {
            try {
                var at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var delay = Duration.between(now, at);
                return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }
}
