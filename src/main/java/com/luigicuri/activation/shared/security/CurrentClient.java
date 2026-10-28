package com.luigicuri.activation.shared.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Identifies the system calling the API, which owns the requests it creates (R8.4).
 *
 * <p>With client credentials the token represents the calling system itself. Its id is read from
 * {@code client_id} (RFC 9068), falling back to {@code azp} (Keycloak, Entra ID) and then {@code sub}.
 */
@Component
public class CurrentClient {

    public String id() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No authenticated client in the security context");
        }
        Jwt jwt = token.getToken();
        for (var claim : new String[] {"client_id", "azp", "sub"}) {
            var value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Token has no client identifier claim");
    }
}
