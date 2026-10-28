package com.luigicuri.activation.request.api;

import static com.luigicuri.activation.support.TestTokens.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import com.luigicuri.activation.support.IntegrationTest;

/** End-to-end through HTTP, JPA and a real PostgreSQL. */
@IntegrationTest
@AutoConfigureMockMvc
class ActivationRequestApiIT {

    private static final String UTC_ISO_8601 = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private static MockHttpServletRequestBuilder post(String url, Object... uriVariables) {
        return MockMvcRequestBuilders.post(url, uriVariables).with(client("client-a"));
    }

    private static MockHttpServletRequestBuilder get(String url, Object... uriVariables) {
        return MockMvcRequestBuilders.get(url, uriVariables).with(client("client-a"));
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE activation_request");
    }

    @Test
    void createdRequestIsPersistedAndCanBeRetrieved() throws Exception {
        var location = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-12345", "CUST-10001", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(UTC_ISO_8601)))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"))
                .andExpect(jsonPath("$.updatedAt").value(matchesPattern(UTC_ISO_8601)));
    }

    @Test
    void locationHeaderPointsToTheCreatedResource() throws Exception {
        var response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-1", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        String id = JsonPath.read(response.getContentAsString(), "$.id");
        assertThat(response.getHeader("Location")).endsWith("/api/v1/requests/" + id);
    }

    @Test
    void timestampsReturnedOnCreationMatchTheStoredOnes() throws Exception {
        var created = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-TIME", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/requests/{id}", (String) JsonPath.read(created, "$.id")))
                .andExpect(jsonPath("$.createdAt").value((String) JsonPath.read(created, "$.createdAt")))
                .andExpect(jsonPath("$.updatedAt").value((String) JsonPath.read(created, "$.updatedAt")));
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/not-found"));
    }

    @Test
    void retryWithSameContentReturns200WithTheSameRequest() throws Exception {
        var first = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-RETRY", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-RETRY", "CUST-1", "INTERNET-500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));

        assertThat(countByExternalId("EXT-RETRY")).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-CONFLICT", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-CONFLICT", "CUST-1", "TV-100")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/duplicate-request"));

        assertThat(countByExternalId("EXT-CONFLICT")).isEqualTo(1);
    }

    @Test
    @DisplayName("R8.4 a client cannot read another client's request: 404, as if it did not exist")
    void anotherClientsRequestIsNotFound() throws Exception {
        var created = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-OWNED", "CUST-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests/{id}", (String) JsonPath.read(created, "$.id"))
                        .with(client("client-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R8.4 listing only shows the caller's requests")
    void listingIsScopedToTheCaller() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXT-A", "CUST-1", "INTERNET-500")));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(client("client-b"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXT-B", "CUST-1", "INTERNET-500")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests").with(client("client-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-B"));
    }

    @Test
    @DisplayName("R4.5 an externalId taken by another client is a 409 that reveals nothing about it")
    void externalIdTakenByAnotherClientReturnsGenericConflict() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-SHARED", "CUST-SECRET", "INTERNET-500")))
                .andExpect(status().isCreated());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(client("client-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-SHARED", "CUST-SECRET", "INTERNET-500")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The externalId is already in use."))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    private Integer countByExternalId(String externalId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activation_request WHERE external_id = ?", Integer.class, externalId);
    }

    static String body(String externalId, String customerId, String productCode) {
        return """
                {"externalId": "%s", "customerId": "%s", "productCode": "%s"}
                """.formatted(externalId, customerId, productCode);
    }
}
