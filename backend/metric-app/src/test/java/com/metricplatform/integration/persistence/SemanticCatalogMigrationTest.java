package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SemanticCatalogMigrationTest {
    @Autowired MigrationMetadataPersistenceService metadata;

    @Test
    void removesLegacyCatalogAndKeepsPublishedCatalog() {
        for (String table :
                List.of(
                        "data_source",
                        "dataset",
                        "dataset_field",
                        "dimension",
                        "metric",
                        "dataset_relationship",
                        "dataset_relationship_field_pair",
                        "dimension_time_granularity",
                        "git_sync_state",
                        "git_object_ownership")) {
            assertThat(tableCount(table)).as(table).isZero();
        }
        for (String table :
                List.of(
                        "semantic_project",
                        "semantic_release",
                        "semantic_resource",
                        "semantic_dependency",
                        "semantic_query",
                        "platform_user",
                        "platform_access_token",
                        "audit_event")) {
            assertThat(tableCount(table)).as(table).isEqualTo(1);
        }
    }

    private int tableCount(String tableName) {
        return metadata.tableCount(tableName);
    }
}
