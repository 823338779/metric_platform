package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.ontology.WarehouseColumns.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WarehouseMetadataPolicyTest {
    final UUID env = UUID.randomUUID();
    final BuildExecutionSnapshot snapshot =
            new BuildExecutionSnapshot(
                    env,
                    new ConnectionTarget("starrocks", "warehouse", "localhost", 9030, null),
                    null,
                    "default_catalog",
                    "env_prod",
                    1,
                    "UTC");
    final WarehouseMetadataPolicy policy =
            new WarehouseMetadataPolicy(
                    List.of(
                            new WarehouseMetadataPolicy.Instance(
                                    "warehouse",
                                    true,
                                    "jdbc:mysql://warehouse:9030",
                                    "reader",
                                    "secret",
                                    "1",
                                    true,
                                    Map.of("tenant", List.of("custom")))));

    @Test
    void resolvesModelAliasFromPinnedBuild() {
        var resolution =
                policy.resolve(
                        "tenant", env, snapshot, Map.of("schema", "env_prod", "alias", "orders"));
        assertThat(resolution.target())
                .isEqualTo(new TableTarget("warehouse", "default_catalog", "env_prod", "orders"));
    }

    @Test
    void differentSchemaSetsHaveDifferentPolicyVersions() {
        var first =
                new WarehouseMetadataPolicy(
                        List.of(
                                new WarehouseMetadataPolicy.Instance(
                                        "warehouse",
                                        true,
                                        "url",
                                        "reader",
                                        "secret",
                                        "1",
                                        true,
                                        Map.of("tenant", List.of("a, b")))));
        var second =
                new WarehouseMetadataPolicy(
                        List.of(
                                new WarehouseMetadataPolicy.Instance(
                                        "warehouse",
                                        true,
                                        "url",
                                        "reader",
                                        "secret",
                                        "1",
                                        true,
                                        Map.of("tenant", List.of("a", "b")))));
        assertThat(first.version()).isNotEqualTo(second.version());
    }

    @Test
    void deniedTargetNeverConnects() {
        assertThat(
                        policy.resolve(
                                        "other",
                                        env,
                                        snapshot,
                                        Map.of("schema", "env_prod", "alias", "orders"))
                                .reason())
                .isEqualTo("TARGET_NOT_ALLOWED");
        assertThat(
                        policy.resolve(
                                        "tenant",
                                        env,
                                        snapshot,
                                        Map.of("schema", "other", "alias", "orders"))
                                .target())
                .isNull();
        assertThat(
                        policy.resolve(
                                        "tenant",
                                        UUID.randomUUID(),
                                        snapshot,
                                        Map.of("schema", "env_prod", "alias", "orders"))
                                .target())
                .isNull();
    }
}
