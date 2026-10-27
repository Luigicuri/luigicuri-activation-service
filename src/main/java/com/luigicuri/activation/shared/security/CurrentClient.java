package com.luigicuri.activation.shared.security;

import org.springframework.stereotype.Component;

/**
 * Identifies the system calling the API, which owns the requests it creates (R8.4).
 * Until authentication is in place every caller is the same local client.
 */
@Component
public class CurrentClient {

    static final String LOCAL_CLIENT = "local";

    public String id() {
        return LOCAL_CLIENT;
    }
}
