package com.luigicuri.activation.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Authenticated calls in MockMvc tests without an authorization server. */
public final class TestTokens {

    private TestTokens() {
    }

    /** Token of a client allowed to create and read, as the authorization server would issue it. */
    public static RequestPostProcessor client(String clientId) {
        return jwt().jwt(jwt -> jwt.claim("client_id", clientId))
                .authorities(new SimpleGrantedAuthority("SCOPE_activation:write"),
                        new SimpleGrantedAuthority("SCOPE_activation:read"));
    }
}
