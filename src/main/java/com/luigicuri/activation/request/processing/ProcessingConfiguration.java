package com.luigicuri.activation.request.processing;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
public class ProcessingConfiguration {

    @Bean
    RetryPolicy retryPolicy(RetryProperties properties) {
        return new RetryPolicy(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** The worker can be switched off, e.g. to run an API-only instance or deterministic tests. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "activation.processing", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
