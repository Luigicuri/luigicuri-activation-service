package com.luigicuri.activation.request.processing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "activation.retry")
public record RetryProperties(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {
}
