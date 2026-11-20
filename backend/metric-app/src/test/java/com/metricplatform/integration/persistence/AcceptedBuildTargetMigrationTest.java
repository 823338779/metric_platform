package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.catalog.AssetGrantRepository.OwnedBuild;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.mapper.AssetGrantMapper;
import com.metricplatform.persistence.store.AssetGrantStore;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AcceptedBuildTargetMigrationTest {
    private static final String URL = "jdbc:tc:postgresql:16-alpine:///accepted_build_target";
    private static final String REPOSITORY = "https://example.test/metrics.git";

    @Test
    void backfillsAcceptedSubmissionsAndLeavesUnknownTargetsForDiscovery() throws Exception {
        try (var source = TestMappers.database(URL);
                var scope = TenantScope.open(TenantFixture.ID)) {
            Flyway.configure().dataSource(source).target("23").load().migrate();
            var tenants = TestMappers.get(source, TenantFixtureMapper.class);
            var fixture = TestMappers.get(source, AssetMigrationFixtureMapper.class);
            var manager = TenantFixture.manager(tenants);
            var environment = UUID.randomUUID();
            TestMappers.get(source, TenantMigrationFixtureMapper.class)
                    .createEnvironment(manager.tenantId(), environment, "migration");
            var accepted = UUID.randomUUID();
            var ambiguous = UUID.randomUUID();
            var legacy = UUID.randomUUID();
            for (var build : List.of(accepted, ambiguous, legacy)) {
                TenantFixture.bind(tenants, environment, build);
            }
            var actor = manager.identityId();
            fixture.submission(
                    manager.tenantId(), actor, environment, REPOSITORY, "main", accepted);
            fixture.submission(
                    manager.tenantId(), actor, environment, REPOSITORY, "main", accepted);
            fixture.submission(
                    manager.tenantId(), actor, environment, REPOSITORY, "one", ambiguous);
            fixture.submission(
                    manager.tenantId(), actor, environment, REPOSITORY, "two", ambiguous);
            fixture.submission(manager.tenantId(), actor, environment, REPOSITORY, "pending", null);

            Flyway.configure().dataSource(source).load().migrate();

            var store = new AssetGrantStore(TestMappers.get(source, AssetGrantMapper.class));
            assertThat(store.targets())
                    .containsExactly(new DeploymentKey(REPOSITORY, environment, "main"));
            assertThat(store.ready(accepted)).isFalse();
            assertThat(store.discover())
                    .containsExactlyInAnyOrder(
                            new OwnedBuild(ambiguous, environment),
                            new OwnedBuild(legacy, environment));
        }
    }
}
