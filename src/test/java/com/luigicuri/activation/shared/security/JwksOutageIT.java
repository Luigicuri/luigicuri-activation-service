package com.luigicuri.activation.shared.security;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.luigicuri.activation.support.TestcontainersConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * R7.5: the authorization server answers discovery but its keys (JWKS) are unavailable, so no token can be
 * checked. That is an outage on our side: 503 with Retry-After, not a 401 blaming the caller's token.
 * Separate from JwtValidationIT so the failed key fetch cannot affect the decoder those tests share.
 */
@SpringBootTest(properties = "activation.processing.enabled=false")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class JwksOutageIT {

    @RegisterExtension
    static WireMockExtension authorizationServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .resetOnEachTest(false)
            .build();

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JwksOutageIT::issuer);
    }

    private static String issuer() {
        return authorizationServer.baseUrl() + "/issuer";
    }

    @BeforeAll
    static void keysUnavailable() {
        authorizationServer.stubFor(get("/issuer/.well-known/openid-configuration").willReturn(okJson("""
                {"issuer": "%s", "jwks_uri": "%s/jwks", "subject_types_supported": ["public"]}
                """.formatted(issuer(), authorizationServer.baseUrl()))));
        authorizationServer.stubFor(get("/jwks").willReturn(serviceUnavailable()));
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("issuer keys unreachable -> 503 with Retry-After, not 401")
    void unreachableKeysAreAnOutageNotAnInvalidToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests")
                        .header("Authorization", "Bearer " + signedToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/service-unavailable"))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests"));
    }

    private static String signedToken() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("orders-system")
                .audience(List.of("activation-service"))
                .claim("client_id", "orders-system")
                .claim("scope", "activation:read")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
