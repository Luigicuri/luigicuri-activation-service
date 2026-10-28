package com.luigicuri.activation.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI description served at /v3/api-docs and explored at /swagger-ui.html. The security scheme is
 * the same client credentials flow other systems use, so "Authorize" in the UI obtains a real token.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static final String SECURITY_SCHEME = "oauth2";

    @Bean
    OpenAPI activationServiceOpenApi(@Value("${openapi.oauth2.token-url}") String tokenUrl) {
        var flow = new OAuthFlow()
                .tokenUrl(tokenUrl)
                .scopes(new Scopes()
                        .addString("activation:write", "Create activation requests")
                        .addString("activation:read", "Read and list activation requests"));
        return new OpenAPI()
                .info(new Info()
                        .title("Activation Service API")
                        .version("v1")
                        .description("Registers product activation requests and activates them through an "
                                + "external provider. Requests are processed asynchronously: creation returns "
                                + "PENDING and the status moves to COMPLETED or FAILED."))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("OAuth 2.0 client credentials. The token's client_id owns the requests it creates.")
                        .flows(new OAuthFlows().clientCredentials(flow))))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
