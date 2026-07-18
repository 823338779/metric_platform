package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AuditColumnsMigrationTest {
    private static final List<String> TABLES =
            List.of(
                    "platform_user",
                    "data_source",
                    "dataset",
                    "dataset_field",
                    "dimension",
                    "dimension_time_granularity",
                    "metric",
                    "dataset_relationship",
                    "dataset_relationship_field_pair",
                    "audit_event");

    @Test
    void migrationAddsEveryAuditColumnToEveryBusinessTable() throws Exception {
        String sql;
        try (var input =
                getClass().getResourceAsStream("/db/migration/V6__standardize_audit_columns.sql")) {
            sql =
                    new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
                            .toLowerCase();
        }

        for (String table : TABLES) {
            assertThat(sql)
                    .contains(
                            "alter table " + table + " add column if not exists created_at",
                            "alter table " + table + " add column if not exists updated_at",
                            "alter table " + table + " add column if not exists is_deleted",
                            "alter table " + table + " add column if not exists deleted_at");
        }
        assertThat(sql)
                .contains("update data_source set is_deleted = true where deleted_at is not null");
    }
}
