package com.luigicuri.activation.shared.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Each client system may make {@code limitForPeriod} API calls per {@code refreshPeriod} (R8.5). */
@ConfigurationProperties(prefix = "activation.rate-limit")
public record RateLimitProperties(int limitForPeriod, Duration refreshPeriod) {
}
