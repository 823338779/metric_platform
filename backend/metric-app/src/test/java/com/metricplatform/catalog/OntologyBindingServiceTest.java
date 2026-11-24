package com.metricplatform.catalog;

import static com.metricplatform.ontology.OntologyContracts.BindingStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.AssetGrantRepository.Asset;
import com.metricplatform.catalog.AssetGrantRepository.Entry;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildContracts.ResourceView;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyStore;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class OntologyBindingServiceTest {
    final Scope scope = new Scope("tn_00001", UUID.randomUUID());
    final UUID actor = UUID.randomUUID(), asset = UUID.randomUUID(), build = UUID.randomUUID();
    final DeploymentKey target = new DeploymentKey("repo", scope.environmentId(), "main");
    final AssetGrantRepository assets = mock(AssetGrantRepository.class);
    final DeployedAssets deployed = mock(DeployedAssets.class);
    final BuildGateway gateway = mock(BuildGateway.class);
    final OntologyStore store = mock(OntologyStore.class);
    final OntologyBindingService service =
            new OntologyBindingService(deployed, assets, gateway, store);
    final Property key = new Property(UUID.randomUUID(), "Order number", "order_id", "bigint");
    final Property buyer = new Property(UUID.randomUUID(), "Buyer", "buyer_id", null);
    final ObjectType order =
            new ObjectType(UUID.randomUUID(), "Order", "", asset, key.id(), List.of(key, buyer));
    final Topology graph = new Topology(2, List.of(order), List.of());
    TenantScope tenant;

    @BeforeEach
    void setup() {
        tenant = TenantScope.open(scope.tenantId());
        var principal =
                new PlatformPrincipal(
                        actor,
                        "admin",
                        "Admin",
                        null,
                        1,
                        scope.tenantId(),
                        PlatformPrincipal.Role.TENANT_MANAGER);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
        when(assets.targets()).thenReturn(List.of(target));
        when(deployed.current(target)).thenReturn(build);
        var pointer = mock(DeployedAssets.ActivePointer.class);
        when(pointer.activeBuildId()).thenReturn(build);
        when(deployed.currentPointer(target)).thenReturn(pointer);
        when(assets.members(build, actor)).thenReturn(List.of(member(asset, target)));
        when(store.saveReport(eq(scope), any())).thenReturn(true);
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
        tenant.close();
    }

    Asset member(UUID id, DeploymentKey deployment) {
        return new Asset(
                id,
                actor,
                deployment,
                new Entry("DATASET", "model.shop.orders", "", "orders", ""),
                true);
    }

    void metadata(UUID version, Map<String, ?> columns) {
        when(gateway.resource(version, "model.shop.orders"))
                .thenReturn(
                        new ResourceView(
                                version,
                                "model.shop.orders",
                                Map.of(
                                        "unique_id",
                                        "model.shop.orders",
                                        "resource_type",
                                        "model",
                                        "columns",
                                        columns),
                                null));
    }

    private OntologyBindingService physical(com.metricplatform.ontology.WarehouseColumns reader) {
        var policy =
                new WarehouseMetadataPolicy(
                        List.of(
                                new WarehouseMetadataPolicy.Instance(
                                        "warehouse",
                                        true,
                                        "jdbc:mysql://fixture",
                                        "test",
                                        "test",
                                        "1",
                                        true,
                                        Map.of(scope.tenantId(), List.of()))));
        var physical = new OntologyBindingService(deployed, assets, gateway, store, policy, reader);
        var snapshot =
                new com.metricplatform.environment.EnvironmentContracts.BuildExecutionSnapshot(
                        scope.environmentId(),
                        new com.metricplatform.environment.EnvironmentContracts.ConnectionTarget(
                                "starrocks", "warehouse", "localhost", 9030, null),
                        null,
                        "default_catalog",
                        "env_prod",
                        1,
                        "UTC");
        var detail = mock(BuildContracts.BuildView.class);
        when(detail.buildId()).thenReturn(build);
        when(detail.execution()).thenReturn(snapshot);
        when(gateway.getBuild(build)).thenReturn(detail);
        when(gateway.resource(build, "model.shop.orders"))
                .thenReturn(
                        new ResourceView(
                                build,
                                "model.shop.orders",
                                Map.of(
                                        "unique_id",
                                        "model.shop.orders",
                                        "resource_type",
                                        "model",
                                        "schema",
                                        "env_prod",
                                        "alias",
                                        "orders",
                                        "columns",
                                        Map.of("order_id", Map.of(), "buyer_id", Map.of())),
                                null));
        when(reader.read(any(), any()))
                .thenReturn(
                        new com.metricplatform.ontology.WarehouseColumns.TableInventory(
                                Map.of("order_id", "bigint(20)", "buyer_id", "varchar(24)"),
                                true,
                                "2026-11-24T12:00:00Z",
                                null));
        return physical;
    }

    @Test
    void readsSelfRelationOnceAndUsesObservedTypes() {
        var reader = mock(com.metricplatform.ontology.WarehouseColumns.class);
        var physical = physical(reader);
        assertThatThrownBy(() -> physical.bindRelation(scope, build, order, key, order, buyer))
                .hasMessageContaining("422");
        verify(reader, times(1)).read(any(), any());
        var columns = physical.columns(scope, asset, build);
        assertThat(columns.columns().getFirst().lastObservation().physicalType())
                .isEqualTo("varchar(24)");
        assertThat(columns.columns().getFirst().declaredType()).isNull();
    }

    @Test
    void staleAcceptanceRequiresFreshCompatibleObservation() {
        var reader = mock(com.metricplatform.ontology.WarehouseColumns.class);
        var physical = physical(reader);
        var previous =
                new Property(
                        buyer.id(),
                        buyer.name(),
                        buyer.columnName(),
                        null,
                        new PropertyEvidence("bigint", null, "MATCHED"));
        var original =
                new ObjectType(
                        order.id(), order.name(), "", asset, key.id(), List.of(key, previous));
        var accept = new TypeAcceptance(buyer.id(), "bigint", "varchar(24)");
        var verified = physical.verifyObject(scope, build, original, Set.of(), List.of(accept));
        var update = verified.observation().updates().getFirst();
        assertThat(update.expectedPrevious().acceptedPhysicalType()).isEqualTo("bigint");
        assertThat(update.value().acceptedPhysicalType()).isEqualTo("varchar(24)");
        assertThat(verified.object()).isEqualTo(original);
        when(reader.read(any(), any()))
                .thenReturn(
                        new com.metricplatform.ontology.WarehouseColumns.TableInventory(
                                Map.of("buyer_id", "varchar(48)"), true, "today", null));
        assertThatThrownBy(
                        () ->
                                physical.verifyObject(
                                        scope, build, original, Set.of(), List.of(accept)))
                .hasMessageContaining("409");
    }

    @Test
    void temporaryFailurePreservesLastSuccessAndKnownConflict() {
        var reader = mock(com.metricplatform.ontology.WarehouseColumns.class);
        var physical = physical(reader);
        var last =
                new ColumnObservation(
                        "varchar(24)",
                        "yesterday",
                        build,
                        new com.metricplatform.ontology.WarehouseColumns.TableTarget(
                                "warehouse", "default_catalog", "env_prod", "orders"));
        var previous =
                new Property(
                        buyer.id(),
                        buyer.name(),
                        buyer.columnName(),
                        null,
                        new PropertyEvidence("bigint", last, "TYPE_CONFLICT", "TYPE_CONFLICT"));
        var original =
                new ObjectType(
                        order.id(), order.name(), "", asset, key.id(), List.of(key, previous));
        when(reader.read(any(), any()))
                .thenReturn(
                        com.metricplatform.ontology.WarehouseColumns.TableInventory.unavailable(
                                "METADATA_TIMEOUT"));
        when(store.saveObservedReport(eq(scope), any(), any())).thenReturn(true);
        var topology = new Topology(2, List.of(original), List.of());
        when(store.read(scope)).thenReturn(topology);
        var report = physical.check(scope, topology, true);
        var finding =
                report.findings().stream()
                        .filter(x -> buyer.id().equals(x.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(finding.status()).isEqualTo(UNVERIFIED);
        assertThat(finding.evidence().lastObservation()).isEqualTo(last);
        assertThatThrownBy(
                        () ->
                                physical.bindRelation(
                                        scope, build, original, key, original, previous))
                .hasMessageContaining("422");
        assertThat(
                        physical.verifyObject(scope, build, order, Set.of(key.id()), List.of())
                                .observation()
                                .updates()
                                .getFirst()
                                .value()
                                .reason())
                .isEqualTo("METADATA_TIMEOUT");
    }

    @Test
    void deniedSecondEndpointOpensNoWarehouseConnection() {
        var reader = mock(com.metricplatform.ontology.WarehouseColumns.class);
        var physical = physical(reader);
        var id = UUID.randomUUID();
        var node = "model.shop.private";
        var second =
                new Asset(id, actor, target, new Entry("DATASET", node, "", "private", ""), true);
        when(assets.members(build, actor)).thenReturn(List.of(member(asset, target), second));
        when(gateway.resource(build, node))
                .thenReturn(
                        new ResourceView(
                                build,
                                node,
                                Map.of(
                                        "unique_id",
                                        node,
                                        "resource_type",
                                        "model",
                                        "schema",
                                        "private",
                                        "alias",
                                        "orders",
                                        "columns",
                                        Map.of("order_id", Map.of())),
                                null));
        var other = new ObjectType(UUID.randomUUID(), "other", "", id, key.id(), List.of(key));
        assertThatThrownBy(() -> physical.bindRelation(scope, build, order, buyer, other, key))
                .hasMessageContaining("403");
        verifyNoInteractions(reader);
    }

    @Test
    void disabledInstanceUsesCurrentDeclarationsDespiteHistoricalDrift() {
        metadata(
                build,
                Map.of(
                        "order_id",
                        Map.of("data_type", "bigint"),
                        "buyer_id",
                        Map.of("data_type", "bigint")));
        var old =
                new Property(
                        buyer.id(),
                        buyer.name(),
                        buyer.columnName(),
                        null,
                        new PropertyEvidence("bigint", null, "TYPE_CONFLICT", "TYPE_CONFLICT"));
        assertThatCode(() -> service.bindRelation(scope, build, order, key, order, old))
                .doesNotThrowAnyException();
    }

    @Test
    void missingColumnIsDefinitiveOnlyWithCompleteInventory() {
        var reader = mock(com.metricplatform.ontology.WarehouseColumns.class);
        var physical = physical(reader);
        when(reader.read(any(), any()))
                .thenReturn(
                        new com.metricplatform.ontology.WarehouseColumns.TableInventory(
                                Map.of("order_id", "bigint"), true, "now", null),
                        new com.metricplatform.ontology.WarehouseColumns.TableInventory(
                                Map.of("order_id", "bigint"), false, "now", null));
        assertThatThrownBy(() -> physical.bindRelation(scope, build, order, key, order, buyer))
                .hasMessageContaining("422");
        assertThat(physical.bindRelation(scope, build, order, key, order, buyer)).isNotNull();
    }

    // 可选的本地验收只加载去标识的旧图与目录快照；所有写入均发生在临时图库。
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "ontology.legacy.fixture",
            matches = ".+")
    void rechecksLegacyGraphWithoutChangingIdentities() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture =
                json.readTree(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of(
                                        System.getProperty("ontology.legacy.fixture"))));
        var objects = List.of(json.treeToValue(fixture.get("objects"), ObjectType[].class));
        var edges = List.of(json.treeToValue(fixture.get("relations"), Relation[].class));
        String password = UUID.randomUUID().toString();
        try (var container =
                new org.testcontainers.containers.GenericContainer<>("neo4j:2026.10.1")
                        .withExposedPorts(7687)
                        .withEnv("NEO4J_AUTH", "neo4j/" + password)
                        .waitingFor(
                                org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                                        ".*Started.*\n", 1))) {
            container.start();
            try (var driver =
                    org.neo4j.driver.GraphDatabase.driver(
                            "bolt://" + container.getHost() + ":" + container.getMappedPort(7687),
                            org.neo4j.driver.AuthTokens.basic("neo4j", password))) {
                var neo = new com.metricplatform.persistence.ontology.Neo4jOntologyStore(driver);
                int revision = 0;
                for (var obj : objects) neo.createObject(scope, revision++, obj);
                for (var edge : edges) neo.createRelation(scope, revision++, edge);
                try (var session = driver.session()) {
                    session.run(
                                    "MATCH (s:OntologyScope) SET s.revision=39 CREATE (c:OntologyCheck {tenantId:s.tenantId,environmentId:s.environmentId,build:$build,revision:39,report:$report})",
                                    Map.of(
                                            "build",
                                            build.toString(),
                                            "report",
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "buildId",
                                                            build,
                                                            "revision",
                                                            39,
                                                            "findings",
                                                            List.of()))))
                            .consume();
                }
                assertThat(neo.readReport(scope, build, 39)).isEmpty();
                var entries = new ArrayList<Asset>();
                var inventories =
                        new HashMap<
                                String,
                                com.metricplatform.ontology.WarehouseColumns.TableInventory>();
                for (var obj : objects) {
                    var data = fixture.get("models").get(obj.datasetAssetId().toString());
                    var alias = data.get("name").asText();
                    var node = "model.fixture." + alias;
                    entries.add(
                            new Asset(
                                    obj.datasetAssetId(),
                                    actor,
                                    target,
                                    new Entry("DATASET", node, "", alias, ""),
                                    true));
                    when(gateway.resource(build, node))
                            .thenReturn(
                                    new ResourceView(
                                            build,
                                            node,
                                            Map.of(
                                                    "unique_id",
                                                    node,
                                                    "resource_type",
                                                    "model",
                                                    "schema",
                                                    "fixture",
                                                    "alias",
                                                    alias,
                                                    "columns",
                                                    json.convertValue(
                                                            data.get("columns"), Map.class)),
                                            null));
                    var types =
                            json.convertValue(
                                    data.get("actual"),
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, String>>() {});
                    inventories.put(
                            alias,
                            new com.metricplatform.ontology.WarehouseColumns.TableInventory(
                                    types, true, "2026-11-24T12:00:00Z", null));
                }
                when(assets.members(build, actor)).thenReturn(entries);
                var detail = mock(BuildContracts.BuildView.class);
                when(detail.buildId()).thenReturn(build);
                when(detail.execution())
                        .thenReturn(
                                new com.metricplatform.environment.EnvironmentContracts
                                        .BuildExecutionSnapshot(
                                        scope.environmentId(),
                                        new com.metricplatform.environment.EnvironmentContracts
                                                .ConnectionTarget(
                                                "starrocks", "fixture", "localhost", 9030, null),
                                        null,
                                        "default_catalog",
                                        "fixture",
                                        1,
                                        "UTC"));
                when(gateway.getBuild(build)).thenReturn(detail);
                var policy =
                        new WarehouseMetadataPolicy(
                                List.of(
                                        new WarehouseMetadataPolicy.Instance(
                                                "fixture",
                                                true,
                                                "jdbc:mysql://fixture",
                                                "fixture",
                                                "fixture",
                                                "1",
                                                true,
                                                Map.of(scope.tenantId(), List.of()))));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var service =
                        new OntologyBindingService(
                                deployed,
                                assets,
                                gateway,
                                neo,
                                policy,
                                (table, access) -> {
                                    calls.incrementAndGet();
                                    return inventories.get(table.table());
                                });
                var original = neo.read(scope);
                assertThat(original.objects()).hasSize(13);
                assertThat(
                                original.objects().stream()
                                        .flatMap(o -> o.properties().stream())
                                        .filter(p -> p.declaredType() == null))
                        .hasSize(63);
                var report = service.check(scope, original);
                assertThat(report.findings())
                        .filteredOn(f -> "PROPERTY".equals(f.kind()))
                        .hasSize(64)
                        .allMatch(f -> f.status() == COMPATIBLE);
                assertThat(report.findings())
                        .filteredOn(f -> "RELATION".equals(f.kind()))
                        .hasSize(21)
                        .allMatch(f -> f.status() == COMPATIBLE);
                assertThat(calls.get()).isEqualTo(13);
                var after = neo.read(scope);
                assertThat(json.valueToTree(after).toString())
                        .isEqualTo(json.valueToTree(original).toString());
                assertThat(service.check(scope, after)).isEqualTo(report);
                assertThat(calls.get()).isEqualTo(13);
            }
        }
    }

    @Test
    void reactivatedBuildCannotReuseOldReport() {
        var physical = physical(mock(com.metricplatform.ontology.WarehouseColumns.class));
        var snapshot = mock(DeployedAssets.ActivePointer.class);
        when(snapshot.activeBuildId()).thenReturn(build);
        when(snapshot.version()).thenReturn(3L);
        when(deployed.currentPointer(target)).thenReturn(snapshot);
        var empty = new Topology(2, List.of(), List.of());
        when(store.read(scope)).thenReturn(empty);
        when(store.saveObservedReport(eq(scope), any(), any())).thenReturn(true);
        var current = physical.check(scope, empty, true);
        when(store.readReport(scope, build, 2)).thenReturn(Optional.of(current));
        when(snapshot.version()).thenReturn(5L);
        var reactivated = physical.check(scope, empty);
        assertThat(reactivated.deploymentVersion()).isEqualTo(5);
        verify(store, times(2)).saveObservedReport(eq(scope), any(), any());
    }

    @Test
    void supersededObservationReturnsWinningReport() {
        var physical = physical(mock(com.metricplatform.ontology.WarehouseColumns.class));
        var empty = new Topology(2, List.of(), List.of());
        when(store.read(scope)).thenReturn(empty);
        when(store.saveObservedReport(eq(scope), any(), any())).thenReturn(true);
        var winner = physical.check(scope, empty, true);
        when(store.saveObservedReport(eq(scope), any(), any())).thenReturn(false);
        when(store.readReport(scope, build, 2)).thenReturn(Optional.of(winner));
        assertThat(physical.check(scope, empty, true)).isEqualTo(winner);
    }

    @Test
    void missingDeclarationsAndUnknownTypesRemainUnverified() {
        metadata(build, Map.of("buyer_id", Map.of("name", "buyer_id")));
        var findings = service.check(scope, graph).findings();
        assertThat(findings)
                .contains(
                        new Finding("PROPERTY", key.id(), UNVERIFIED, "COLUMN_UNDECLARED"),
                        new Finding("PROPERTY", buyer.id(), UNVERIFIED, "TYPE_UNKNOWN"));
    }

    @Test
    void sameAssetInNewBuildIsMatchedByIdAndExplicitTypeChangeNeedsRepair() {
        var next = UUID.randomUUID();
        when(deployed.current(target)).thenReturn(next);
        when(assets.members(next, actor)).thenReturn(List.of(member(asset, target)));
        metadata(next, Map.of("order_id", Map.of("data_type", "text")));
        var report = service.check(scope, graph);
        assertThat(report.buildId()).isEqualTo(next);
        assertThat(report.findings())
                .contains(new Finding("PROPERTY", key.id(), NEEDS_REPAIR, "TYPE_CONFLICT"));
        verify(gateway, never()).resource(eq(build), any());
    }

    @Test
    void otherEnvironmentNeverSuppliesCandidatesWithSameName() {
        var other = new DeploymentKey("repo", UUID.randomUUID(), "main");
        when(assets.targets()).thenReturn(List.of(target, other));
        when(assets.members(build, actor))
                .thenReturn(List.of(member(asset, target), member(UUID.randomUUID(), other)));
        assertThat(service.datasets(scope).datasets()).hasSize(1);
        verify(deployed, never()).current(other);
    }

    @Test
    void currentBuildAndRevisionReuseSuccessfulEvidenceButRetryTransientFailures() {
        var cached =
                new BindingReport(
                        build,
                        graph.revision(),
                        List.of(new Finding("OBJECT", order.id(), COMPATIBLE, "MATCHED")));
        when(store.readReport(scope, build, graph.revision())).thenReturn(Optional.of(cached));
        assertThat(service.check(scope, graph)).isEqualTo(cached);
        verifyNoInteractions(gateway);

        var failed =
                new BindingReport(
                        build,
                        graph.revision(),
                        List.of(
                                new Finding(
                                        "OBJECT", order.id(), UNVERIFIED, "UPSTREAM_UNAVAILABLE")));
        when(store.readReport(scope, build, graph.revision())).thenReturn(Optional.of(failed));
        metadata(build, Map.of("order_id", Map.of("data_type", "bigint")));
        assertThat(service.check(scope, graph).findings())
                .contains(new Finding("PROPERTY", key.id(), COMPATIBLE, "MATCHED"));
    }

    @Test
    void finalBuildCheckFollowsGraphWrite() {
        metadata(build, Map.of());
        doAnswer(
                        call -> {
                            when(deployed.current(target)).thenReturn(UUID.randomUUID());
                            return true;
                        })
                .when(store)
                .saveReport(eq(scope), any());
        var report = service.check(scope, graph);
        assertThat(report.buildId()).isNull();
        assertThat(report.findings())
                .contains(new Finding("OBJECT", order.id(), UNVERIFIED, "DEPLOYMENT_CHANGED"));
    }

    @Test
    void relationNeedsDeclaredColumnsAndRejectsOnlyEstablishedTypeConflicts() {
        metadata(build, Map.of("order_id", Map.of("data_type", "bigint"), "buyer_id", Map.of()));
        service.bindRelation(scope, build, order, key, order, buyer);
        metadata(
                build,
                Map.of(
                        "order_id",
                        Map.of("data_type", "bigint"),
                        "buyer_id",
                        Map.of("data_type", "text")));
        assertThatThrownBy(() -> service.bindRelation(scope, build, order, key, order, buyer))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
        metadata(build, Map.of("order_id", Map.of("data_type", "bigint")));
        assertThatThrownBy(() -> service.bindRelation(scope, build, order, key, order, buyer))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }

    @Test
    void serviceCredentialFailuresKeepTheirNonretryableGatewayError() {
        var error =
                new BuildGateway.Failure(
                        502,
                        new ErrorView(
                                "SERVICE_AUTHENTICATION_FAILED",
                                "dbt-service authentication failed",
                                false,
                                null,
                                null));
        when(gateway.resource(build, "model.shop.orders")).thenThrow(error);
        assertThatThrownBy(() -> service.columns(scope, asset, build))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> {
                            assertThat(ex.getStatusCode().value()).isEqualTo(502);
                            assertThat(ex.getBody().getProperties())
                                    .containsEntry("code", "SERVICE_AUTHENTICATION_FAILED")
                                    .containsEntry("retryable", false);
                        });
        var report = service.check(scope, graph);
        assertThat(report.findings())
                .contains(
                        new Finding(
                                "OBJECT", order.id(), UNVERIFIED, "SERVICE_AUTHENTICATION_FAILED"));
        when(store.readReport(scope, build, graph.revision())).thenReturn(Optional.of(report));
        reset(gateway);
        metadata(build, Map.of("order_id", Map.of("data_type", "bigint")));
        assertThat(service.check(scope, graph).findings())
                .contains(new Finding("PROPERTY", key.id(), COMPATIBLE, "MATCHED"));
        when(deployed.current(target)).thenThrow(error);
        assertThatThrownBy(() -> service.datasets(scope))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void noDeploymentAndOfflineAreDistinguishedWithoutDiscardingDefinitions() {
        when(deployed.current(target)).thenReturn(null);
        assertThat(service.check(scope, graph).findings())
                .contains(new Finding("PROPERTY", key.id(), UNVERIFIED, "NO_DEPLOYMENT"));
        when(deployed.current(target)).thenThrow(new IllegalStateException("private details"));
        assertThat(service.check(scope, graph).findings())
                .contains(new Finding("OBJECT", order.id(), UNVERIFIED, "UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void staleChecksCannotBeReturnedAgainstNewerEditsOrDeployments() {
        metadata(build, Map.of("order_id", Map.of("data_type", "int8")));
        when(store.saveReport(eq(scope), any())).thenReturn(false);
        assertThatThrownBy(() -> service.check(scope, graph))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        when(store.saveReport(eq(scope), any())).thenReturn(true);
        when(deployed.current(target)).thenReturn(build, UUID.randomUUID());
        var report = service.check(scope, graph);
        assertThat(report.findings())
                .contains(new Finding("OBJECT", order.id(), UNVERIFIED, "DEPLOYMENT_CHANGED"));
    }
}
