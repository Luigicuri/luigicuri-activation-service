package com.luigicuri.activation.request.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luigicuri.activation.support.IntegrationTest;

/**
 * R4.4: identical requests arriving at the same time, against a real PostgreSQL. With a
 * check-then-insert implementation this test fails intermittently with duplicated rows.
 */
@IntegrationTest
class DuplicateRequestConcurrencyIT {

    private static final int CONCURRENT_REQUESTS = 20;

    @Autowired
    ActivationRequestService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE activation_request");
    }

    @Test
    @DisplayName("R4.4 concurrent identical requests create exactly one record")
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        var command = new CreateActivationRequestCommand("EXT-RACE", "CUST-1", "INTERNET-500");
        var startSignal = new CountDownLatch(1);
        List<Future<CreationResult>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                futures.add(executor.submit((Callable<CreationResult>) () -> {
                    startSignal.await();
                    return service.create(command, "client-a");
                }));
            }
            startSignal.countDown();

            List<CreationResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(CreationResult.Created.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(CreationResult.AlreadyExists.class::isInstance)
                    .hasSize(CONCURRENT_REQUESTS - 1);
            assertThat(results).extracting(result -> result.request().getId()).containsOnly(
                    results.getFirst().request().getId());
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activation_request WHERE external_id = 'EXT-RACE'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
