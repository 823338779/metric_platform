package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthenticationOnlyMigrationTest {
    @Autowired JdbcTemplate jdbc;

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
        return jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class,
                tableName);
    }

    private int columnCount(String tableName, String columnName) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = ?",
                Integer.class,
                tableName,
                columnName);
    }
}
