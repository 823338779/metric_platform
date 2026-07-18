package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SemanticCatalogMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsRelationshipAndGranularityTables() {
        assertThat(tableCount("dataset_relationship")).isEqualTo(1);
        assertThat(tableCount("dimension_time_granularity")).isEqualTo(1);
    }

    private int tableCount(String tableName) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class,
                tableName);
    }
}
