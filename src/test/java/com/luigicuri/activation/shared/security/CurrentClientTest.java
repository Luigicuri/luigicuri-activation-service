package com.luigicuri.activation.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** R8.4: which claim identifies the calling system, across authorization servers. */
class CurrentClientTest {

    private final CurrentClient currentClient = new CurrentClient();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesClientIdFromRfc9068Tokens() {
        authenticate(Map.of("client_id", "orders-system", "azp", "other", "sub", "other"));

        assertThat(currentClient.id()).isEqualTo("orders-system");
    }

    @Test
    void fallsBackToAzpUsedByKeycloakAndEntraId() {
        authenticate(Map.of("azp", "orders-system", "sub", "service-account-123"));

        assertThat(currentClient.id()).isEqualTo("orders-system");
    }

    @Test
    void fallsBackToSubject() {
        authenticate(Map.of("sub", "orders-system"));

        assertThat(currentClient.id()).isEqualTo("orders-system");
    }

    @Test
    void blankClaimsAreIgnored() {
        authenticate(Map.of("client_id", " ", "sub", "orders-system"));

        assertThat(currentClient.id()).isEqualTo("orders-system");
    }

    @Test
    void tokenWithoutAnyClientClaimIsRejected() {
        authenticate(Map.of("scope", "activation:read"));

        assertThatThrownBy(currentClient::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonJwtAuthenticationIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "password"));

        assertThatThrownBy(currentClient::id).isInstanceOf(IllegalStateException.class);
    }

    private static void authenticate(Map<String, Object> claims) {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(all -> all.putAll(claims))
                .issuedAt(Instant.now())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
