package com.luigicuri.activation.request.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.luigicuri.activation.request.application.ActivationRequestNotFoundException;
import com.luigicuri.activation.request.application.ActivationRequestQuery;
import com.luigicuri.activation.request.application.ActivationRequestService;
import com.luigicuri.activation.request.application.CreateActivationRequestCommand;
import com.luigicuri.activation.request.application.CreationResult;
import com.luigicuri.activation.request.application.DuplicateActivationRequestException;
import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.shared.security.CurrentClient;
import com.luigicuri.activation.shared.security.SecurityConfiguration;

/**
 * Web layer only: HTTP contract, validation, error format and security (R1, R2, R7, R8). The service is
 * mocked; tokens are injected with spring-security-test, so no authorization server is needed.
 */
@WebMvcTest(ActivationRequestController.class)
@Import({SecurityConfiguration.class, CurrentClient.class})
class ActivationRequestControllerTest {

    private static final String CLIENT = "client-a";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final RequestPostProcessor WRITER = token("SCOPE_activation:write");
    private static final RequestPostProcessor READER = token("SCOPE_activation:read");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ActivationRequestService service;

    /** Only reached by requests carrying a raw bearer token; jwt() bypasses decoding. */
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static RequestPostProcessor token(String... authorities) {
        return jwt().jwt(jwt -> jwt.claim("client_id", CLIENT))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    /** Requests below are authorized unless a test says otherwise; security has its own nested class. */
    private static MockHttpServletRequestBuilder post(String url, Object... uriVariables) {
        return MockMvcRequestBuilders.post(url, uriVariables).with(WRITER);
    }

    private static MockHttpServletRequestBuilder get(String url, Object... uriVariables) {
        return MockMvcRequestBuilders.get(url, uriVariables).with(READER);
    }

    private static ActivationRequest request() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", CLIENT, "trace", null,
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    private static String body(String externalId, String customerId, String productCode) {
        return ActivationRequestApiIT.body(externalId, customerId, productCode);
    }

    @Nested
    class Create {

        @Test
        void validRequestReturns201WithLocation() throws Exception {
            var created = request();
            given(service.create(eq(new CreateActivationRequestCommand("EXT-12345", "CUST-10001", "INTERNET-500")),
                    eq(CLIENT))).willReturn(new CreationResult.Created(created));

            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-12345", "CUST-10001", "INTERNET-500")))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/api/v1/requests/" + created.getId())))
                    .andExpect(jsonPath("$.id").value(created.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.failureReason").doesNotExist());
        }

        @Test
        void retryOfAnExistingRequestReturns200WithoutLocation() throws Exception {
            var existing = request();
            given(service.create(any(), eq(CLIENT))).willReturn(new CreationResult.AlreadyExists(existing));

            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-12345", "CUST-10001", "INTERNET-500")))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.id").value(existing.getId().toString()));
        }

        @Test
        void conflictingDuplicateReturns409() throws Exception {
            given(service.create(any(), eq(CLIENT)))
                    .willThrow(new DuplicateActivationRequestException("The externalId is already in use."));

            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-12345", "CUST-10001", "INTERNET-500")))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/duplicate-request"))
                    .andExpect(jsonPath("$.detail").value("The externalId is already in use."));
        }

        @Test
        void missingAndInvalidFieldsReturn400WithFieldErrors() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"externalId": "EXT 1", "productCode": "%s"}
                                    """.formatted("X".repeat(65))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/validation-error"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.errors[*].field",
                            contains("customerId", "externalId", "productCode")))
                    .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("is required"));
        }

        @Test
        void blankFieldReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("   ", "CUST-1", "INTERNET-500")))
                    .andExpect(status().isBadRequest())
                    // Two violations on the same field come back in a stable order too (by message)
                    .andExpect(jsonPath("$.errors[*].field", contains("externalId", "externalId")))
                    .andExpect(jsonPath("$.errors[0].message").value("is required"))
                    .andExpect(jsonPath("$.errors[1].message")
                            .value("must contain only letters, digits, '.', '_' or '-'"));
        }

        @Test
        void malformedJsonReturns400WithoutParserDetails() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"externalId\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/malformed-request"))
                    .andExpect(jsonPath("$.detail").value(not(containsString("Jackson"))));
        }
    }

    @Nested
    class Get {

        @Test
        void existingRequestReturns200() throws Exception {
            var existing = request();
            given(service.get(existing.getId(), CLIENT)).willReturn(existing);

            mockMvc.perform(get("/api/v1/requests/{id}", existing.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void unknownRequestReturns404() throws Exception {
            var id = UUID.randomUUID();
            given(service.get(id, CLIENT)).willThrow(new ActivationRequestNotFoundException(id));

            mockMvc.perform(get("/api/v1/requests/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/not-found"));
        }

        @Test
        void malformedIdReturns404() throws Exception {
            mockMvc.perform(get("/api/v1/requests/{id}", "not-a-uuid"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/not-found"));
        }
    }

    @Nested
    class Listing {

        @Test
        void filtersAndPaginationAreForwardedToTheService() throws Exception {
            var existing = request();
            var query = new ActivationRequestQuery(ActivationStatus.PENDING, "CUST-10001", 2, 5);
            given(service.list(query, CLIENT))
                    .willReturn(new PageImpl<>(List.of(existing), PageRequest.of(2, 5), 11));

            mockMvc.perform(get("/api/v1/requests")
                            .param("status", "PENDING")
                            .param("customerId", "CUST-10001")
                            .param("page", "2")
                            .param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(existing.getId().toString()))
                    .andExpect(jsonPath("$.page").value(2))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.totalElements").value(11))
                    .andExpect(jsonPath("$.totalPages").value(3));
        }

        @Test
        void invalidStatusReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/requests").param("status", "DONE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[0].field").value("status"));
        }

        @Test
        void sizeAboveMaximumReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/requests").param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/validation-error"))
                    .andExpect(jsonPath("$.errors[0].field").value("size"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be between 1 and 100"));
        }

        @Test
        void negativePageReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/requests").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("page"));
        }
    }

    @Nested
    class Security {

        @Test
        @DisplayName("R8.2 no token -> 401 problem with WWW-Authenticate")
        void requestWithoutTokenIsRejectedWith401() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests/{id}", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/unauthorized"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @DisplayName("R8.2 invalid token -> 401 without the decoder's reason")
        void invalidTokenIsRejectedWith401() throws Exception {
            given(jwtDecoder.decode("not-a-jwt")).willThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests/{id}", UUID.randomUUID())
                            .header("Authorization", "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString("Invalid signature"))));
        }

        @Test
        @DisplayName("R8.3 read-only token cannot create -> 403")
        void readScopeCannotCreate() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests").with(READER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-1", "CUST-1", "INTERNET-500")))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/forbidden"));
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("R8.3 write-only token cannot read -> 403")
        void writeScopeCannotRead() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests").with(WRITER))
                    .andExpect(status().isForbidden());
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests/{id}", UUID.randomUUID()).with(WRITER))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("URL rejected by the security firewall -> 400 problem, not 500")
        void urlRejectedByTheFirewallIsA400Problem() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/requests;jsessionid=abc").with(READER))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/malformed-request"));
        }

        @Test
        @DisplayName("R8.4 the owner is taken from the token's client_id")
        void ownerComesFromTheToken() throws Exception {
            var created = request();
            given(service.create(any(), eq(CLIENT))).willReturn(new CreationResult.Created(created));

            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-12345", "CUST-10001", "INTERNET-500")))
                    .andExpect(status().isCreated());

            verify(service).create(any(), eq(CLIENT));
        }
    }

    @Nested
    class Errors {

        @Test
        void unexpectedErrorReturns500WithoutInternalDetails() throws Exception {
            given(service.get(any(), any()))
                    .willThrow(new IllegalStateException("could not execute statement [select * from secret_table]"));

            mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/internal-error"))
                    .andExpect(content().string(not(containsString("secret_table"))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))));
        }

        @Test
        void suppliedCorrelationIdIsEchoedAndReturnedInErrors() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .header("X-Correlation-Id", "abc-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(header().string("X-Correlation-Id", "abc-123"))
                    .andExpect(jsonPath("$.correlationId").value("abc-123"));
        }

        @Test
        void correlationIdOutsideTheAllowedFormatIsReplaced() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .header("X-Correlation-Id", "<script>alert(1)</script>")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(header().string("X-Correlation-Id", matchesPattern("[a-f0-9]{32}")));
        }

        @Test
        void headerWithControlCharactersIsRejectedAs400() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .header("X-Correlation-Id", "abc\nFAKE LOG LINE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/malformed-request"))
                    .andExpect(header().string("X-Correlation-Id", not(containsString("FAKE"))));
        }

        @Test
        @DisplayName("R8.5 body above 16 KB -> 413 before reaching the controller")
        void oversizedBodyIsRejectedWith413() throws Exception {
            var padding = "x".repeat(17 * 1024);

            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("EXT-1", "CUST-1", "INTERNET-500").replace("}", ", \"pad\": \"" + padding + "\"}")))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.luigicuri.dev/problems/payload-too-large"));
            verifyNoInteractions(service);
        }

        @Test
        void unsupportedMethodAlsoUsesProblemDetailWithTraceId() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/requests/{id}", UUID.randomUUID()).with(WRITER))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }
}
