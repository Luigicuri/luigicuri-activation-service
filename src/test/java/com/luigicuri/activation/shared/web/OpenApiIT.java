package com.luigicuri.activation.shared.web;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.luigicuri.activation.support.IntegrationTest;

/** The API description is public and tells clients how to authenticate; the operations it lists are not. */
@IntegrationTest
@AutoConfigureMockMvc
class OpenApiIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDescriptionIsPublicAndAnnouncesTheClientCredentialsFlow() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/requests'].post.summary").value("Create an activation request"))
                .andExpect(jsonPath("$.components.securitySchemes.oauth2.flows.clientCredentials.tokenUrl")
                        .value(endsWith("/token")))
                .andExpect(jsonPath("$.components.securitySchemes.oauth2.flows.clientCredentials.scopes['activation:write']")
                        .exists())
                .andExpect(jsonPath("$.security[0].oauth2").exists());
    }

    @Test
    void swaggerUiIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void operationsListedThereStillRequireAToken() throws Exception {
        mockMvc.perform(get("/api/v1/requests"))
                .andExpect(status().isUnauthorized());
    }
}
