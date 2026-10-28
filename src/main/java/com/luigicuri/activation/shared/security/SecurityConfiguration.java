package com.luigicuri.activation.shared.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.error.Problems;

/**
 * OAuth 2.0 Resource Server (ADR-0004): every API call needs a JWT, validated for signature, issuer,
 * expiry and audience. Scopes separate who may create requests from who may only read them.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String SCOPE_WRITE = "SCOPE_activation:write";
    static final String SCOPE_READ = "SCOPE_activation:read";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        var problemHandler = new SecurityProblemHandler(objectMapper);
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
                .build();
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
