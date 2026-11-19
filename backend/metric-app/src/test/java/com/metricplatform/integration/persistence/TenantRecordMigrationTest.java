package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantRecordMigrationTest {
    private static final String URL = "jdbc:tc:postgresql:16-alpine:///tenant_records";
    private static final String USER = "test";
    private static final String FIRST = "tn_00001";
    private static final String SECOND = "tn_00002";
    private static final String CREATE_TENANT =
            """
            with tenant as (
                insert into platform_tenant(tenant_id,manager_id) values(?,?) returning *
            )
            insert into platform_user(id,username,display_name,tenant_id)
            select manager_id,tenant_id,tenant_id,tenant_id from tenant
            """;
    private static final String INSERT_EVENT =
            """
            insert into audit_event(id,actor_id,action,resource_type,resource_id,effective_identity_id)
            values(?,'bootstrap','LOGIN_FAILED','login_attempt',?,?)
            """;
    private static final String ARCHIVE =
            """
            create table tenant_cutover_submission_archive as
            select *, 'archived'::text as archive_status, now() as archived_at
            from environment_build_submission
            """;
    private static final String INVALID_OWNER_COLUMNS =
            """
            select t.relname from pg_class t join pg_namespace s on s.oid=t.relnamespace
            where s.nspname=current_schema() and t.relkind='r'
              and t.relname not in ('flyway_schema_history','platform_tenant')
              and not exists (
                  select from pg_attribute a join pg_constraint fk
                    on fk.conrelid=t.oid and fk.conkey=array[a.attnum]
                  where a.attrelid=t.oid and a.attname='tenant_id' and a.attnotnull
                    and not a.atthasdef and col_description(a.attrelid,a.attnum) is not null
                    and fk.contype='f' and fk.confrelid='platform_tenant'::regclass
              )
            """;
    private static final String INSERT_QUERY_AUDIT =
            """
            insert into query_execution_audit(tenant_id,execution_id,build_id,user_id)
            values(?,gen_random_uuid(),?,?)
            """;
    private static final String INSERT_PROJECT =
            """
            insert into semantic_project(tenant_id,project_id,repository_id,project_subdir,profile_binding_id)
            values(?,'shared','repo','.','binding')
            """;

    @Test
    void preservesAuditEvidenceAndBindsHistoricalAndNewRowsToTheirOwners() throws Exception {
        try (var keepAlive = DriverManager.getConnection(URL, USER, USER)) {
            var source = new DriverManagerDataSource(URL, USER, USER);
            Flyway.configure().dataSource(source).target("18").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID anonymousEvent = UUID.randomUUID();
            UUID unknownAccount = UUID.randomUUID();
            jdbc.update(INSERT_EVENT, anonymousEvent, unknownAccount, unknownAccount);
            Flyway.configure().dataSource(source).target("19").load().migrate();

            // V19 之后再开通两个租户，子表应从真实父记录恢复归属。
            UUID firstUser = UUID.randomUUID(), secondUser = UUID.randomUUID();
            UUID environment = UUID.randomUUID(), build = UUID.randomUUID();
            jdbc.update(CREATE_TENANT, FIRST, firstUser);
            jdbc.update(CREATE_TENANT, SECOND, secondUser);
            jdbc.update(
                    "insert into"
                            + " execution_environment(environment_id,name,config_version,tenant_id)"
                            + " values(?,'env',1,?)",
                    environment,
                    SECOND);
            jdbc.update("insert into build_ownership values(?,?,?)", build, environment, SECOND);
            jdbc.update(
                    "insert into"
                            + " environment_build_submission(actor_id,idempotency_key,environment_id,command,request,build_id)"
                            + " values(?,'key',?,'{}','{}',?)",
                    secondUser,
                    environment,
                    build);
            jdbc.execute(ARCHIVE);
            jdbc.update(
                    "insert into query_execution_audit(execution_id,build_id,user_id)"
                            + " values(gen_random_uuid(),?,?)",
                    build,
                    secondUser);

            // 两个租户都存在时，切换后的无主体事件需人工核对。升级失败不得破坏原始证据。
            UUID unassignedEvent = UUID.randomUUID();
            jdbc.update(INSERT_EVENT, unassignedEvent, unknownAccount, unknownAccount);
            var upgrade = Flyway.configure().dataSource(source).load();
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("Unresolved tenant ownership in audit_event");
            assertThat(
                            jdbc.queryForObject(
                                    "select effective_identity_id from audit_event where id=?",
                                    UUID.class,
                                    unassignedEvent))
                    .isEqualTo(unknownAccount);
            jdbc.execute("alter table audit_event add column tenant_id varchar(8)");
            jdbc.update("update audit_event set tenant_id=? where id=?", SECOND, unassignedEvent);
            upgrade.migrate();

            assertThat(jdbc.queryForList(INVALID_OWNER_COLUMNS, String.class)).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from history_cursor", Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select tenant_id from audit_event where id=? and resource_id=?"
                                            + " and effective_identity_id is null",
                                    String.class,
                                    anonymousEvent,
                                    unknownAccount))
                    .isEqualTo(FIRST);
            assertThat(
                            jdbc.queryForList(
                                    "select tenant_id from tenant_cutover_submission_archive union"
                                            + " all select tenant_id from query_execution_audit",
                                    String.class))
                    .containsExactly(SECOND, SECOND);

            // 新数据必须显式归属；任一方账号或构建串租户都应由 PostgreSQL 拒绝。
            assertThatThrownBy(() -> jdbc.update(INSERT_QUERY_AUDIT, SECOND, build, firstUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(INSERT_QUERY_AUDIT, FIRST, build, firstUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(INSERT_QUERY_AUDIT, null, build, secondUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(INSERT_QUERY_AUDIT, SECOND, build, secondUser);
            jdbc.update(INSERT_PROJECT, FIRST);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update semantic_project set tenant_id=? where"
                                                    + " tenant_id=?",
                                            SECOND,
                                            FIRST))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("tenant ownership is immutable");
            jdbc.update(INSERT_PROJECT, SECOND);
        }
    }
}
