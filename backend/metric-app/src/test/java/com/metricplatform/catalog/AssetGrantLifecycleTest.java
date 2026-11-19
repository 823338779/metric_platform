package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.catalog.AssetGrantRepository.Entry;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AssetGrantLifecycleTest {
    @Autowired AssetGrantRepository store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void grantsSurviveRemovalAndRedeploymentUnlessExplicitlyRevoked() {
        var env = UUID.randomUUID();
        var target = new DeploymentKey("https://example.test/assets.git", env, "main");
        var reader = UUID.randomUUID();
        var owner = TenantFixture.manager(jdbc);
        jdbc.update(
                "insert into platform_user(id,tenant_id,username,display_name) values(?,?,?,?)",
                reader,
                owner.tenantId(),
                reader.toString(),
                "reader");
        var metric = new Entry("METRIC", "metric.pkg.sales", "", "sales", "");
        try (var scope = TenantScope.open(owner.tenantId())) {
            var first = publish(target, List.of(metric));
            var asset = store.members(first, reader).getFirst();
            assertThat(asset.ownerId()).isEqualTo(owner.identityId());
            store.grant(reader, asset.assetId());
            var otherBranch = new DeploymentKey(target.repository(), env, "other");
            assertThat(store.members(publish(otherBranch, List.of(metric)), reader))
                    .singleElement()
                    .satisfies(
                            other -> {
                                assertThat(other.granted()).isFalse();
                                assertThat(other.assetId()).isNotEqualTo(asset.assetId());
                            });
            assertThat(store.members(first, reader)).allMatch(item -> item.granted());
            assertThat(store.members(publish(target, List.of()), reader)).isEmpty();
            var restored = publish(target, List.of(metric));
            assertThat(store.members(restored, reader))
                    .singleElement()
                    .satisfies(
                            item -> {
                                assertThat(item.assetId()).isEqualTo(asset.assetId());
                                assertThat(item.granted()).isTrue();
                            });
            publish(target, List.of());
            store.revoke(reader, asset.assetId());
            assertThat(store.members(publish(target, List.of(metric)), reader))
                    .singleElement()
                    .satisfies(item -> assertThat(item.granted()).isFalse());
        }
    }

    private UUID publish(DeploymentKey target, List<Entry> entries) {
        UUID build = UUID.randomUUID();
        TenantFixture.bind(jdbc, target.environmentId(), build);
        store.bind(build, target);
        store.register(build, target, entries);
        store.register(build, target, entries);
        return build;
    }
}
