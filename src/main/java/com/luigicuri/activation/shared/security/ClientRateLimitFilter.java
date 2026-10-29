package com.luigicuri.activation.shared.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.error.Problems;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * Limits how many API calls each client system makes (R8.5), counted per token {@code client_id}. Runs after
 * authorization, so rejected calls do not count. The counters live in each instance's memory: with N
 * instances a client can make up to N times the limit, which is why the gateway still enforces the global one.
 */
class ClientRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiters;
    private final CurrentClient currentClient;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;

    ClientRateLimitFilter(RateLimiterRegistry rateLimiters, CurrentClient currentClient, ObjectMapper objectMapper,
                          RateLimitProperties properties) {
        this.rateLimiters = rateLimiters;
        this.currentClient = currentClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimiters.rateLimiter(currentClient.id()).acquirePermission()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds()));
        Problems.write(request, response, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests",
                "Too many requests", "This client exceeded %d requests per %s. Try again after the Retry-After seconds."
                        .formatted(properties.limitForPeriod(), readable(properties.refreshPeriod()))), objectMapper);
    }

    /** {@code PT1S} reads as {@code 1s}, {@code PT1M} as {@code 1m}. */
    private static String readable(Duration period) {
        return period.toString().substring(2).toLowerCase(Locale.ROOT);
    }

    /** The window restarts at most one refresh period from now. */
    private long retryAfterSeconds() {
        return Math.max(1, (properties.refreshPeriod().toMillis() + 999) / 1000);
    }
}
