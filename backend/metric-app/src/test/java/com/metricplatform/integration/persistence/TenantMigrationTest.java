package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantMigrationTest {
    @Test
    void emptyDatabaseReservesFirstNumberForProvisioning() throws Exception {
        String url = "jdbc:tc:postgresql:16-alpine:///empty_tenancy";
        try (var keepAlive = DriverManager.getConnection(url, "test", "test")) {
            var source = new DriverManagerDataSource(url, "test", "test");
            Flyway.configure().dataSource(source).load().migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(
                            jdbc.queryForObject(
                                    "select to_regclass('platform_tenant')::text", String.class))
                    .isEqualTo("platform_tenant");
            assertThat(jdbc.queryForObject("select count(*) from platform_tenant", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select nextval('tenant_number')", Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void existingAccountsRequireVerifiedInventoryAndEligibleManager() throws Exception {
        String url = "jdbc:tc:postgresql:16-alpine:///legacy_tenancy";
        try (var keepAlive = DriverManager.getConnection(url, "test", "test")) {
            var source = new DriverManagerDataSource(url, "test", "test");
            Flyway.configure().dataSource(source).target("18").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID manager = UUID.randomUUID();
            String insert =
                    "insert into platform_user(id,username,display_name,password_hash)"
                            + " values(?,?,?,?)";
            jdbc.update(insert, manager, "manager", "Manager", "fixture");
            jdbc.update(insert, UUID.randomUUID(), "reader", "Reader", "fixture");
            var upgrade = Flyway.configure().dataSource(source).load();
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("tenant migration inventory");
            jdbc.execute(
                    "create table tenant_cutover_input(manager_login text, environment_ids uuid[],"
                            + " builds jsonb)");
            jdbc.update("insert into tenant_cutover_input values('manager','{}','[]')");
            UUID build = UUID.randomUUID(), env = UUID.randomUUID();
            jdbc.update(
                    "insert into query_execution_audit(execution_id,build_id,user_id)"
                            + " values(?,?,?)",
                    UUID.randomUUID(),
                    build,
                    manager);
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("missing historical build IDs");
            jdbc.update(
                    "update tenant_cutover_input set environment_ids=array[?]::uuid[],"
                            + " builds=jsonb_build_array(jsonb_build_object('build_id',?::uuid,'environment_id',?::uuid))",
                    env,
                    build,
                    env);
            upgrade.migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "select tenant_id from build_ownership where build_id=?",
                                    String.class,
                                    build))
                    .isEqualTo("tn_00001");
            assertThat(
                            jdbc.queryForObject(
                                    "select manager_id from platform_tenant where"
                                            + " tenant_id='tn_00001'",
                                    UUID.class))
                    .isEqualTo(manager);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from platform_user where tenant_id='tn_00001'",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("select nextval('tenant_number')", Long.class))
                    .isEqualTo(2L);
        }
    }
}
