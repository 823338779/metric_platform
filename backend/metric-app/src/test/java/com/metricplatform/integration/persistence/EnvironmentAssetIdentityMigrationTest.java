package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.mapper.AssetGrantMapper;
import com.metricplatform.persistence.store.AssetGrantStore;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class EnvironmentAssetIdentityMigrationTest {
    private static final String URL = "jdbc:tc:postgresql:16-alpine:///asset_migration";
    private static final String OLD = "old", NEW = "new";

    @Test
    void migratesExistingBranchGrantsAcrossPackagesWithinTheirEnvironment() throws Exception {
        try (var source = TestMappers.database(URL);
                var tenant = TenantScope.open(TenantFixture.ID)) {
            Flyway.configure().dataSource(source).target("22").load().migrate();
            var tenants = TestMappers.get(source, TenantFixtureMapper.class);
            var fixture = TestMappers.get(source, AssetMigrationFixtureMapper.class);
            var owner = TenantFixture.manager(tenants);
            var reader = UUID.randomUUID();
            fixture.reader(owner.tenantId(), reader, "test");

            // 新旧仓库的同名指标与生产环境各有旧身份；存量账号只获开发环境旧分支的授权。
            var dev = UUID.randomUUID();
            var oldBuild = legacyMetric(tenants, fixture, dev, OLD);
            var devBuild = legacyMetric(tenants, fixture, dev, NEW);
            var prodBuild = legacyMetric(tenants, fixture, UUID.randomUUID(), NEW);
            fixture.grantBuildMembers(owner.tenantId(), reader, oldBuild);
            Flyway.configure().dataSource(source).load().migrate();

            var store = new AssetGrantStore(TestMappers.get(source, AssetGrantMapper.class));
            Asset old = store.members(oldBuild, reader).getFirst();
            Asset current = store.members(devBuild, reader).getFirst();
            Asset prod = store.members(prodBuild, reader).getFirst();
            assertThat(current.assetId()).isEqualTo(old.assetId());
            assertThat(current.granted()).isTrue();
            assertThat(current.entry().nodeId()).isEqualTo("metric.new.product_count");
            assertThat(prod.assetId()).isNotEqualTo(current.assetId());
            assertThat(prod.granted()).isFalse();
            assertThat(store.list(reader)).isEmpty();
            store.observe(dev, devBuild, store.nextScan());
            assertThat(store.list(reader))
                    .singleElement()
                    .satisfies(item -> assertThat(item.asset()).isEqualTo(current));
        }
    }

    private static UUID legacyMetric(
            TenantFixtureMapper tenants,
            AssetMigrationFixtureMapper fixture,
            UUID environment,
            String source) {
        var build = UUID.randomUUID();
        var owner = TenantFixture.bind(tenants, environment, build);
        var id = UUID.randomUUID();
        fixture.legacyScope(owner.tenantId(), build, environment, source);
        fixture.legacyMetric(owner.tenantId(), id, owner.identityId(), environment, source);
        fixture.legacyMember(owner.tenantId(), build, id);
        return build;
    }
}
