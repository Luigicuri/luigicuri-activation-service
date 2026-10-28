package com.luigicuri.activation.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the external activation provider (R5.4). */
@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
}
