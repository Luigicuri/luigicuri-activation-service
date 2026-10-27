package com.luigicuri.activation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luigicuri.activation.support.IntegrationTest;

@IntegrationTest
class ActivationServiceApplicationIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndFlywayCreatesSchema() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'activation_request'",
                Integer.class);

        assertThat(tables).isEqualTo(1);
    }
}
