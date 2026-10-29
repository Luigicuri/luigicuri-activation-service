package com.luigicuri.activation.shared.security;

import java.time.Duration;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.error.Problems;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * OAuth 2.0 Resource Server (ADR-0004): every API call needs a JWT, validated for signature, issuer,
 * expiry and audience. Scopes separate who may create requests from who may only read them, and each
 * client system has a call rate limit.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfiguration {

    static final String SCOPE_WRITE = "SCOPE_activation:write";
    static final String SCOPE_READ = "SCOPE_activation:read";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper objectMapper, CurrentClient currentClient,
                                    RateLimiterRegistry rateLimiters, RateLimitProperties rateLimit)
            throws Exception {
        var problemHandler = new SecurityProblemHandler(objectMapper);
        // After authorization: only calls that would be served count against the client's limit
        var rateLimitFilter = new ClientRateLimitFilter(rateLimiters, currentClient, objectMapper, rateLimit);
        return http
                // Stateless API authenticated by bearer tokens: no session and no cookies, so no CSRF exposure
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Actuator is served on a separate management port that is not exposed publicly (R8.6)
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        // API documentation is public; every operation it lists still requires a token
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
                        .hasAuthority(SCOPE_READ)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)
                .build();
    }

    /** One limiter per client id, created on first use; non-blocking, so an excess call is refused at once. */
    @Bean
    RateLimiterRegistry clientRateLimiters(RateLimitProperties properties) {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(properties.limitForPeriod())
                .limitRefreshPeriod(properties.refreshPeriod())
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    /**
     * Spring Security's firewall rejects malformed requests (e.g. control characters in headers). That is
     * the client's fault, so it is a 400 problem rather than the generic 500 it would otherwise become.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> Problems.write(request, response,
                Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                        "The request contains characters that are not allowed."), objectMapper);
    }
}
