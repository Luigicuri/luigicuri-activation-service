package com.luigicuri.activation.request.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.luigicuri.activation.request.application.ActivationRequestNotFoundException;
import com.luigicuri.activation.request.application.ActivationRequestQuery;
import com.luigicuri.activation.request.application.ActivationRequestService;
import com.luigicuri.activation.request.application.CreateActivationRequestCommand;
import com.luigicuri.activation.request.application.CreationResult;
import com.luigicuri.activation.request.application.DuplicateActivationRequestException;
import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.shared.security.CurrentClient;

/** Web layer only: HTTP contract, validation and error format (R1, R2, R7). The service is mocked. */
@WebMvcTest(ActivationRequestController.class)
class ActivationRequestControllerTest {

    private static final String CLIENT = "client-a";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ActivationRequestService service;

    @MockitoBean
    CurrentClient currentClient;

    @BeforeEach
    void setUp() {
        given(currentClient.id()).willReturn(CLIENT);
    }

    private static ActivationRequest request() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", CLIENT, "trace",
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
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.errors[*].field",
                            containsInAnyOrder("externalId", "customerId", "productCode")))
                    .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("is required"));
        }

        @Test
        void blankFieldReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("   ", "CUST-1", "INTERNET-500")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("externalId"));
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
        void suppliedCorrelationIdIsEchoedAndUsedAsTraceId() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .header("X-Correlation-Id", "abc-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(header().string("X-Correlation-Id", "abc-123"))
                    .andExpect(jsonPath("$.traceId").value("abc-123"));
        }

        @Test
        void unsafeCorrelationIdIsReplaced() throws Exception {
            mockMvc.perform(post("/api/v1/requests")
                            .header("X-Correlation-Id", "abc\nFAKE LOG LINE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(header().string("X-Correlation-Id", not(containsString("FAKE"))));
        }

        @Test
        void unsupportedMethodAlsoUsesProblemDetailWithTraceId() throws Exception {
            mockMvc.perform(delete("/api/v1/requests/{id}", UUID.randomUUID()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }
}
