package com.luigicuri.activation.request.processing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param leaseTimeout how long a request may stay PROCESSING before it is considered abandoned
 *                     (instance crashed mid-call) and released for another attempt
 * @param credentialsRejectedDelay how long a request waits after the provider rejected our credentials;
 *                                 it keeps being retried at this pace, without using attempts, until fixed
 */
@ConfigurationProperties(prefix = "activation.processing")
public record ProcessingProperties(boolean enabled, Duration pollInterval, int batchSize, Duration leaseTimeout,
                                   Duration reconciliationInterval, Duration credentialsRejectedDelay) {
}
