package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TenantMigrationTest {
    @Test
    void emptyDatabaseReservesFirstNumberForProvisioning() throws Exception {
        String url = "jdbc:tc:postgresql:16-alpine:///empty_tenancy";
        try (var source = TestMappers.database(url)) {
            Flyway.configure().dataSource(source).load().migrate();
            var fixture = TestMappers.get(source, TenantMigrationFixtureMapper.class);
            assertThat(fixture.tenantTable()).isEqualTo("platform_tenant");
            assertThat(fixture.countTenants()).isZero();
            assertThat(fixture.nextTenantNumber()).isEqualTo(1L);
        }
    }

    @Test
    void existingAccountsRequireVerifiedInventoryAndEligibleManager() throws Exception {
        String url = "jdbc:tc:postgresql:16-alpine:///legacy_tenancy";
        try (var source = TestMappers.database(url)) {
            Flyway.configure().dataSource(source).target("18").load().migrate();
            var fixture = TestMappers.get(source, TenantMigrationFixtureMapper.class);
            UUID manager = UUID.randomUUID();
            fixture.legacyAccount(manager, "manager", "Manager");
            fixture.legacyAccount(UUID.randomUUID(), "reader", "Reader");
            var upgrade = Flyway.configure().dataSource(source).load();
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("tenant migration inventory");
            fixture.createInventory();
            fixture.emptyInventory("manager");
            UUID build = UUID.randomUUID(), env = UUID.randomUUID();
            fixture.legacyQuery(build, manager);
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("missing historical build IDs");
            fixture.setInventoryBuild(env, build);
            upgrade.migrate();
            assertThat(fixture.buildTenant(build)).isEqualTo("tn_00001");
            assertThat(fixture.tenantManager("tn_00001")).isEqualTo(manager);
            assertThat(fixture.countUsers("tn_00001")).isEqualTo(2);
            assertThat(fixture.nextTenantNumber()).isEqualTo(2L);
        }
    }
}
