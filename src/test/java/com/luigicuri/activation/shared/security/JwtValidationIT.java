package com.luigicuri.activation.shared.security;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.luigicuri.activation.support.TestcontainersConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * R8.1 with real, signed tokens: the configured decoder discovers the issuer's JWKS (served by WireMock,
 * standing in for the authorization server) and checks signature, issuer, expiry and audience.
 */
@SpringBootTest(properties = "activation.processing.enabled=false")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class JwtValidationIT {

    @RegisterExtension
    static WireMockExtension authorizationServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .resetOnEachTest(false)
            .build();

    private static RSAKey signingKey;
    private static RSAKey unknownKey;

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JwtValidationIT::issuer);
    }

    private static String issuer() {
        return authorizationServer.baseUrl() + "/issuer";
    }

    @BeforeAll
    static void publishKeys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        unknownKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        authorizationServer.stubFor(get("/issuer/.well-known/openid-configuration").willReturn(okJson("""
                {"issuer": "%s", "jwks_uri": "%s/jwks", "subject_types_supported": ["public"]}
                """.formatted(issuer(), authorizationServer.baseUrl()))));
        authorizationServer.stubFor(get("/jwks").willReturn(okJson(new JWKSet(signingKey.toPublicJWK()).toString())));
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("valid token -> accepted")
    void validTokenIsAccepted() throws Exception {
        callWith(token(signingKey, claims -> { })).andExpect(status().isOk());
    }

    @Test
    @DisplayName("expired token -> 401")
    void expiredTokenIsRejected() throws Exception {
        callWith(token(signingKey, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token issued for another API (audience) -> 401")
    void wrongAudienceIsRejected() throws Exception {
        callWith(token(signingKey, claims -> claims.audience("billing-service")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token from another issuer -> 401")
    void wrongIssuerIsRejected() throws Exception {
        callWith(token(signingKey, claims -> claims.issuer("https://evil.example.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token signed with a key the issuer does not publish -> 401")
    void forgedSignatureIsRejected() throws Exception {
        callWith(token(unknownKey, claims -> { })).andExpect(status().isUnauthorized());
    }

    private ResultActions callWith(String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests")
                .header("Authorization", "Bearer " + token));
    }

    private static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("orders-system")
                .audience(List.of("activation-service"))
                .claim("client_id", "orders-system")
                .claim("scope", "activation:read activation:write")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        customizer.accept(claims);

        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
