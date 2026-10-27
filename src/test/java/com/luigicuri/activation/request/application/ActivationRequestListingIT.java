package com.luigicuri.activation.request.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luigicuri.activation.request.domain.ActivationRequest;
import com.luigicuri.activation.request.domain.ActivationStatus;
import com.luigicuri.activation.request.persistence.ActivationRequestRepository;
import com.luigicuri.activation.support.IntegrationTest;

/** R3 against PostgreSQL: filters, newest-first ordering, pagination and owner scoping. */
@IntegrationTest
class ActivationRequestListingIT {

    private static final Instant T0 = Instant.parse("2026-10-26T14:00:00Z");

    @Autowired
    ActivationRequestService service;

    @Autowired
    ActivationRequestRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE activation_request");
        save("EXT-1", "CUST-1", "client-a", 1, false);
        save("EXT-2", "CUST-1", "client-a", 2, true);
        save("EXT-3", "CUST-2", "client-a", 3, false);
        save("EXT-4", "CUST-1", "client-a", 4, true);
        save("EXT-5", "CUST-1", "client-b", 5, false);
    }

    @Test
    void listsOnlyTheCallersRequestsNewestFirst() {
        var page = service.list(new ActivationRequestQuery(null, null, 0, 20), "client-a");

        assertThat(page.getContent()).extracting(ActivationRequest::getExternalId)
                .containsExactly("EXT-4", "EXT-3", "EXT-2", "EXT-1");
    }

    @Test
    void filtersByCustomer() {
        var page = service.list(new ActivationRequestQuery(null, "CUST-1", 0, 20), "client-a");

        assertThat(page.getContent()).extracting(ActivationRequest::getExternalId)
                .containsExactly("EXT-4", "EXT-2", "EXT-1");
    }

    @Test
    void filtersByStatusAndCustomerCombined() {
        var page = service.list(new ActivationRequestQuery(ActivationStatus.PROCESSING, "CUST-1", 0, 20),
                "client-a");

        assertThat(page.getContent()).extracting(ActivationRequest::getExternalId)
                .containsExactly("EXT-4", "EXT-2");
    }

    @Test
    void paginates() {
        var secondPage = service.list(new ActivationRequestQuery(null, null, 1, 3), "client-a");

        assertThat(secondPage.getContent()).extracting(ActivationRequest::getExternalId).containsExactly("EXT-1");
        assertThat(secondPage.getTotalElements()).isEqualTo(4);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
    }

    private void save(String externalId, String customerId, String clientId, int minutesAfterT0, boolean processing) {
        var createdAt = T0.plusSeconds(60L * minutesAfterT0);
        var request = ActivationRequest.create(externalId, customerId, "INTERNET-500", clientId, null, createdAt);
        if (processing) {
            request.startProcessing(createdAt);
        }
        repository.save(request);
    }
}
