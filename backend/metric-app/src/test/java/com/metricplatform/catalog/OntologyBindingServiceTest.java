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
