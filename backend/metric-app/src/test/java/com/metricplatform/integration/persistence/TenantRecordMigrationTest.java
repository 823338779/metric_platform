package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class TenantRecordMigrationTest {
    private static final String URL = "jdbc:tc:postgresql:16-alpine:///tenant_records";
    private static final String FIRST = "tn_00001";
    private static final String SECOND = "tn_00002";

    @Test
    void preservesAuditEvidenceAndBindsHistoricalAndNewRowsToTheirOwners() throws Exception {
        try (var source = TestMappers.database(URL)) {
            Flyway.configure().dataSource(source).target("18").load().migrate();
            var fixture = TestMappers.get(source, TenantMigrationFixtureMapper.class);
            UUID anonymousEvent = UUID.randomUUID();
            UUID unknownAccount = UUID.randomUUID();
            fixture.legacyEvent(anonymousEvent, unknownAccount);
            Flyway.configure().dataSource(source).target("19").load().migrate();

            // V19 之后再开通两个租户，子表应从真实父记录恢复归属。
            UUID firstUser = UUID.randomUUID(), secondUser = UUID.randomUUID();
            UUID environment = UUID.randomUUID(), build = UUID.randomUUID();
            fixture.createTenant(FIRST, firstUser);
            fixture.createTenant(SECOND, secondUser);
            fixture.createEnvironment(SECOND, environment, "env");
            fixture.ownBuild(SECOND, environment, build);
            fixture.legacySubmission(secondUser, environment, build);
            fixture.archiveSubmissions();
            fixture.legacyQuery(build, secondUser);

            // 两个租户都存在时，切换后的无主体事件需人工核对。升级失败不得破坏原始证据。
            UUID unassignedEvent = UUID.randomUUID();
            fixture.legacyEvent(unassignedEvent, unknownAccount);
            var upgrade = Flyway.configure().dataSource(source).load();
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("Unresolved tenant ownership in audit_event");
            assertThat(fixture.eventIdentity(unassignedEvent)).isEqualTo(unknownAccount);
            fixture.addAuditTenantColumn();
            fixture.assignAuditEvent(unassignedEvent, SECOND);
            upgrade.migrate();

            assertThat(fixture.invalidOwnerColumns()).isEmpty();
            assertThat(fixture.countHistoryCursors()).isZero();
            assertThat(fixture.anonymousEventTenant(anonymousEvent, unknownAccount))
                    .isEqualTo(FIRST);
            assertThat(fixture.migratedEvidenceTenants()).containsExactly(SECOND, SECOND);

            // 新数据必须显式归属；任一方账号或构建串租户都应由 PostgreSQL 拒绝。
            assertThatThrownBy(() -> fixture.recordQuery(SECOND, build, firstUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> fixture.recordQuery(FIRST, build, firstUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> fixture.recordQuery(null, build, secondUser))
                    .isInstanceOf(DataIntegrityViolationException.class);
            fixture.recordQuery(SECOND, build, secondUser);
            fixture.createProject(FIRST);
            assertThatThrownBy(() -> fixture.reassignProjects(FIRST, SECOND))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("tenant ownership is immutable");
            fixture.createProject(SECOND);
        }
    }
}
