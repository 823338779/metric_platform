package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.metricplatform.catalog.AssetGrantRepository.Entry;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class AssetGrantLifecycleTest {
    @Autowired AssetGrantRepository store;
    @Autowired TenantFixtureMapper tenants;
    @Autowired CatalogFixtureMapper fixtures;

    @Test
    void schedulerEnumeratesOwnersBeforeOpeningEachTenantScan() {
        var owner = TenantFixture.bind(tenants, UUID.randomUUID(), UUID.randomUUID());
        assertThat(store.tenants()).contains(owner.tenantId());
        assertThatIllegalStateException().isThrownBy(store::nextScan);
        try (var scope = TenantScope.open(owner.tenantId())) {
            assertThatIllegalStateException().isThrownBy(store::tenants);
            assertThat(store.nextScan()).isPositive();
        }
    }

    @Test
    void grantsSurviveRemovalAndRedeploymentUnlessExplicitlyRevoked() {
        var env = UUID.randomUUID();
        var target = new DeploymentKey("https://example.test/assets.git", env, "main");
        var reader = UUID.randomUUID();
        var owner = TenantFixture.manager(tenants);
        var metric = new Entry("METRIC", "metric.pkg.sales", "", "sales", "");
        try (var scope = TenantScope.open(owner.tenantId())) {
            fixtures.insertReader(reader, "reader");
            var first = publish(target, List.of(metric));
            var asset = store.members(first, reader).getFirst();
            assertThat(asset.ownerId()).isEqualTo(owner.identityId());
            store.grant(reader, asset.assetId());
            long observed = store.nextScan();
            store.observe(env, first, observed);
            assertThat(store.list(reader))
                    .anyMatch(item -> first.equals(item.build()) && item.asset().granted());
            store.observe(env, null, store.nextScan());
            store.observe(env, first, observed);
            assertThat(store.list(reader))
                    .noneMatch(item -> asset.assetId().equals(item.asset().assetId()));
            var otherBranch = new DeploymentKey("https://example.test/new.git", env, "other");
            var moved = new Entry("METRIC", "metric.new_pkg.sales", "", "sales", "");
            var branchBuild = publish(otherBranch, List.of(moved));
            store.observe(env, branchBuild, store.nextScan());
            store.observe(env, first, observed);
            assertThat(store.list(reader))
                    .filteredOn(item -> item.asset().target().environmentId().equals(env))
                    .singleElement()
                    .satisfies(
                            item -> {
                                assertThat(item.build()).isEqualTo(branchBuild);
                                assertThat(item.asset().assetId()).isEqualTo(asset.assetId());
                                assertThat(item.asset().target()).isEqualTo(otherBranch);
                                assertThat(item.asset().entry().nodeId()).isEqualTo(moved.nodeId());
                                assertThat(item.asset().granted()).isTrue();
                            });
            var prod =
                    new DeploymentKey(
                            otherBranch.repository(), UUID.randomUUID(), otherBranch.branchName());
            assertThat(store.members(publish(prod, List.of(moved)), reader))
                    .singleElement()
                    .satisfies(
                            other -> {
                                assertThat(other.granted()).isFalse();
                                assertThat(other.assetId()).isNotEqualTo(asset.assetId());
                            });
            // 同名维度只在相同语义模型内继承授权，数据集也不能继承同名指标的使用权。
            var orders =
                    new Entry("DIMENSION", "semantic_model.old.orders", "region", "orders", "");
            var products =
                    new Entry("DIMENSION", "semantic_model.new.products", "region", "products", "");
            var dataset = new Entry("DATASET", "model.new.sales", "", "sales", "fqn:new.sales");
            var dimension = store.members(publish(target, List.of(orders)), reader).getFirst();
            store.grant(reader, dimension.assetId());
            var nextOrders =
                    new Entry("DIMENSION", "semantic_model.new.orders", "region", "orders", "");
            var next =
                    store.members(
                            publish(otherBranch, List.of(nextOrders, products, dataset)), reader);
            assertThat(next)
                    .filteredOn(item -> item.granted())
                    .singleElement()
                    .satisfies(
                            item -> {
                                assertThat(item.entry()).isEqualTo(nextOrders);
                                assertThat(item.assetId()).isEqualTo(dimension.assetId());
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

    @Test
    void rollsBackEarlierBatchesAndRetriesTheWholeBuild() {
        var build = UUID.randomUUID();
        var environment = UUID.randomUUID();
        var target = new DeploymentKey("repo", environment, "main");
        var owner = TenantFixture.bind(tenants, environment, build);
        var entries = new ArrayList<Entry>();
        for (int index = 0; index < 201; index++) {
            String name = "metric_" + index;
            entries.add(new Entry("METRIC", "metric.pkg." + name, "", name, ""));
        }
        var invalid = new ArrayList<>(entries);
        invalid.set(200, entries.getFirst());

        try (var scope = TenantScope.open(owner.tenantId())) {
            store.bind(build, target);
            assertThatThrownBy(() -> store.register(build, target, invalid))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(store.ready(build)).isFalse();
            assertThat(store.members(build, owner.identityId())).isEmpty();

            store.register(build, target, entries);
            assertThat(store.ready(build)).isTrue();
            assertThat(store.members(build, owner.identityId()))
                    .extracting(asset -> asset.entry())
                    .containsExactlyInAnyOrderElementsOf(entries);
        }
    }

    private UUID publish(DeploymentKey target, List<Entry> entries) {
        UUID build = UUID.randomUUID();
        TenantFixture.bind(tenants, target.environmentId(), build);
        store.bind(build, target);
        store.register(build, target, entries);
        store.register(build, target, entries);
        return build;
    }
}
