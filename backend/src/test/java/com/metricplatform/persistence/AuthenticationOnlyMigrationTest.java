package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthenticationOnlyMigrationTest {
    @Autowired MigrationMetadataPersistenceService metadata;

    @Test
    void removesAuthorizationAndTenancySchema() {
        assertThat(tableCount("platform_user")).isEqualTo(1);
        assertThat(tableCount("platform_access_token")).isEqualTo(1);
        assertThat(columnCount("platform_user", "tenant_id")).isZero();
        assertThat(columnCount("platform_user", "role_code")).isZero();
        assertThat(columnCount("platform_access_token", "audience")).isZero();
        assertThat(columnCount("audit_event", "client_id")).isZero();
        assertThat(tableCount("platform_tenant")).isZero();
        assertThat(tableCount("platform_workspace")).isZero();
        assertThat(tableCount("platform_user_workspace")).isZero();
        assertThat(tableCount("platform_identity_dataset_grant")).isZero();
        assertThat(tableCount("api_client")).isZero();
    }

    private int tableCount(String tableName) {
        return metadata.tableCount(tableName);
    }

    private int columnCount(String tableName, String columnName) {
        return metadata.columnCount(tableName, columnName);
    }
}
