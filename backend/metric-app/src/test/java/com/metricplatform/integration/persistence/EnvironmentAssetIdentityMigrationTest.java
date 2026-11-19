package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.store.AssetGrantStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;

class EnvironmentAssetIdentityMigrationTest {
    private static final String URL = "jdbc:tc:postgresql:16-alpine:///asset_migration";
    private static final String USER = "test", OLD = "old", NEW = "new";

    @Test
    void migratesExistingBranchGrantsAcrossPackagesWithinTheirEnvironment() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, USER);
                var tenant = TenantScope.open(TenantFixture.ID)) {
            var source = new DriverManagerDataSource(URL, USER, USER);
            Flyway.configure().dataSource(source).target("22").load().migrate();
            var jdbc = new JdbcTemplate(source);
            var owner = TenantFixture.manager(jdbc);
            var reader = UUID.randomUUID();
            jdbc.update(
                    "insert into platform_user(id,tenant_id,username,display_name) values(?,?,?,?)",
                    reader,
                    owner.tenantId(),
                    reader.toString(),
                    USER);

            // 新旧仓库的同名指标与生产环境各有旧身份；存量账号只获开发环境旧分支的授权。
            var dev = UUID.randomUUID();
            var oldBuild = legacyMetric(jdbc, dev, OLD);
            var devBuild = legacyMetric(jdbc, dev, NEW);
            var prodBuild = legacyMetric(jdbc, UUID.randomUUID(), NEW);
            jdbc.update(
                    "insert into asset_user_grant select tenant_id,?,asset_id from"
                        + " asset_build_member where build_id=?",
                    reader,
                    oldBuild);
            Flyway.configure().dataSource(source).load().migrate();

            var store = new AssetGrantStore(jdbc);
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

    private static UUID legacyMetric(JdbcTemplate jdbc, UUID environment, String source) {
        var build = UUID.randomUUID();
        var owner = TenantFixture.bind(jdbc, environment, build);
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into asset_build_scope values(?,?,?,?,?,true)",
                owner.tenantId(),
                build,
                environment,
                source,
                source);
        jdbc.update(
                "insert into deployed_asset values(?,?,?,?,?,?,'METRIC',?,'')",
                owner.tenantId(),
                id,
                owner.identityId(),
                environment,
                source,
                source,
                "metric." + source + ".product_count");
        jdbc.update(
                "insert into asset_build_member values(?,?,?,'product_count','')",
                owner.tenantId(),
                build,
                id);
        return build;
    }
}
