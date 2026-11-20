package com.metricplatform.ontology.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.api.ApiExceptionHandler;
import com.metricplatform.catalog.*;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.environment.*;
import com.metricplatform.environment.EnvironmentContracts.EnvironmentView;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.ontology.*;
import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OntologyControllerTest {
    final Scope scope = new Scope("tn_00001", UUID.randomUUID());
    final String base = "/api/dbt/environments/" + scope.environmentId() + "/ontology";
    final EnvironmentStore environments = mock(EnvironmentStore.class);
    final OntologyStore store = mock(OntologyStore.class);
    final OntologyBindingService bindings = mock(OntologyBindingService.class);
    final ObjectMapper json = new ObjectMapper();
    final MockMvc mvc = controller(bindings);

    MockMvc controller(OntologyBindingService verifier) {
        return MockMvcBuilders.standaloneSetup(
                        new OntologyController(
                                new OntologyService(
                                        new EnvironmentService(environments, "{}"),
                                        store,
                                        verifier)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void setup() {
        when(environments.get(scope.tenantId(), scope.environmentId()))
                .thenReturn(new EnvironmentView(scope.environmentId(), "dev", 1, null));
        login(scope.tenantId(), PlatformPrincipal.Role.TENANT_MANAGER);
    }

    void login(String tenant, PlatformPrincipal.Role role) {
        var actor =
                new PlatformPrincipal(UUID.randomUUID(), "actor", "actor", null, 1, tenant, role);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingLoginReadOnlyAndForeignManagerCannotReachGraph() throws Exception {
        clear();
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        login(scope.tenantId(), PlatformPrincipal.Role.READ_ONLY);
        mvc.perform(get(base + "/datasets")).andExpect(status().isForbidden());
        login("tn_00002", PlatformPrincipal.Role.TENANT_MANAGER);
        when(environments.get("tn_00002", scope.environmentId()))
                .thenThrow(new NoSuchElementException("环境不存在"));
        mvc.perform(get(base)).andExpect(status().isNotFound());
        verifyNoInteractions(store, bindings);
    }

    @Test
    void managerReadsCheckedGraphAndReceivesRevisionConflict() throws Exception {
        var graph = new Topology(3, List.of(), List.of());
        when(store.read(scope)).thenReturn(graph);
        when(bindings.check(scope, graph)).thenReturn(new BindingReport(null, 3, List.of()));
        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.bindingReport.revision").value(3));
        var property = new Property(UUID.randomUUID(), "ID", "id", null);
        var object =
                new ObjectType(
                        UUID.randomUUID(),
                        "Order",
                        "",
                        UUID.randomUUID(),
                        property.id(),
                        List.of(property));
        mvc.perform(
                        post(base + "/objects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsBytes(
                                                Map.of("expectedRevision", 1, "object", object))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        verify(store, never()).createObject(any(), anyLong(), any());
    }

    @Test
    void replacingReferencedDatasetReportsBlockersBeforeContactingDeployment() throws Exception {
        var key = new Property(UUID.randomUUID(), "ID", "id", "BIGINT");
        var order =
                new ObjectType(
                        UUID.randomUUID(), "Order", "", UUID.randomUUID(), key.id(), List.of(key));
        var link =
                new Relation(
                        UUID.randomUUID(),
                        "Parent order",
                        "",
                        order.id(),
                        key.id(),
                        order.id(),
                        key.id(),
                        Cardinality.MANY_TO_ONE);
        when(store.read(scope)).thenReturn(new Topology(5, List.of(order), List.of(link)));
        var replacement =
                new ObjectType(
                        order.id(), order.name(), "", UUID.randomUUID(), key.id(), List.of(key));
        mvc.perform(
                        put(base + "/objects/" + order.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRevision",
                                                        5,
                                                        "object",
                                                        replacement))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.references[0].id").value(link.id().toString()));
        verifyNoInteractions(bindings);
    }

    @Test
    void renameDoesNotPerformUpstreamMetadataReads() throws Exception {
        var property = new Property(UUID.randomUUID(), "ID", "id", "BIGINT");
        var original =
                new ObjectType(
                        UUID.randomUUID(),
                        "Order",
                        "",
                        UUID.randomUUID(),
                        property.id(),
                        List.of(property));
        var renamed =
                new ObjectType(
                        original.id(),
                        "Purchase",
                        "",
                        original.datasetAssetId(),
                        property.id(),
                        List.of(property));
        when(store.read(scope)).thenReturn(new Topology(3, List.of(original), List.of()));
        when(store.updateObject(scope, 3, renamed)).thenReturn(4L);
        var payload = json.valueToTree(Map.of("expectedRevision", 3, "object", renamed));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.at("/object/properties/0"))
                .remove("declaredType");
        mvc.perform(
                        put(base + "/objects/" + original.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(4));
        verifyNoInteractions(bindings);
    }

    @Test
    void selectingAnExistingPropertyAsPrimaryChecksItsCurrentBinding() throws Exception {
        var oldKey = new Property(UUID.randomUUID(), "ID", "id", null);
        var nextKey = new Property(UUID.randomUUID(), "Code", "code", null);
        var old =
                new ObjectType(
                        UUID.randomUUID(),
                        "Order",
                        "",
                        UUID.randomUUID(),
                        oldKey.id(),
                        List.of(oldKey, nextKey));
        var updated =
                new ObjectType(
                        old.id(),
                        old.name(),
                        old.description(),
                        old.datasetAssetId(),
                        nextKey.id(),
                        old.properties());
        var build = UUID.randomUUID();
        when(store.read(scope)).thenReturn(new Topology(4, List.of(old), List.of()));
        when(bindings.bindObject(scope, build, updated, Set.of(nextKey.id())))
                .thenThrow(
                        new OntologyProblem(
                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "COLUMN_UNDECLARED",
                                "当前构建未声明选定的字段"));
        mvc.perform(
                        put(base + "/objects/" + old.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRevision",
                                                        4,
                                                        "sourceBuildId",
                                                        build,
                                                        "object",
                                                        updated))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COLUMN_UNDECLARED"));
        verify(store, never()).updateObject(any(), anyLong(), any());
    }

    @Test
    void switchedDeploymentAndForeignEndpointsCannotBeSaved() throws Exception {
        var assets = mock(AssetGrantRepository.class);
        var deployed = mock(DeployedAssets.class);
        var gateway = mock(BuildGateway.class);
        var live = controller(new OntologyBindingService(deployed, assets, gateway, store));
        var target = new DeploymentKey("repo", scope.environmentId(), "main");
        var graph = new Topology(0, List.of(), List.of());
        when(store.read(scope)).thenReturn(graph);
        when(assets.targets()).thenReturn(List.of(target));
        when(deployed.current(target)).thenReturn(UUID.randomUUID());
        var key = new Property(UUID.randomUUID(), "ID", "id", null);
        var object =
                new ObjectType(
                        UUID.randomUUID(), "Order", "", UUID.randomUUID(), key.id(), List.of(key));
        try (var ignored = TenantScope.open(scope.tenantId())) {
            live.perform(
                            post(base + "/objects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsBytes(
                                                    Map.of(
                                                            "expectedRevision",
                                                            0,
                                                            "sourceBuildId",
                                                            UUID.randomUUID(),
                                                            "object",
                                                            object))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEPLOYMENT_CHANGED"));
            var relation =
                    new Relation(
                            UUID.randomUUID(),
                            "Customer",
                            "",
                            object.id(),
                            key.id(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            Cardinality.MANY_TO_ONE);
            live.perform(
                            post(base + "/relations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsBytes(
                                                    Map.of(
                                                            "expectedRevision",
                                                            0,
                                                            "relation",
                                                            relation))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.references").isEmpty());
        }
        verify(store, never()).createObject(any(), anyLong(), any());
        verify(store, never()).createRelation(any(), anyLong(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void invalidMappingScopedReferencesAndGraphOutageHaveActionableResponses() throws Exception {
        when(store.read(scope)).thenReturn(new Topology(0, List.of(), List.of()));
        mvc.perform(
                        post(base + "/objects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":0,\"object\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID"));
        var id = UUID.randomUUID();
        var reference = new Reference(UUID.randomUUID(), "买家");
        when(store.deleteObject(scope, 1, id))
                .thenThrow(
                        new OntologyException(
                                OntologyException.Code.CONFLICT, "请先移除引用", List.of(reference)));
        mvc.perform(delete(base + "/objects/" + id).param("expectedRevision", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.references.length()").value(1))
                .andExpect(jsonPath("$.references[0].id").value(reference.id().toString()));
        when(store.read(scope))
                .thenThrow(new OntologyException(OntologyException.Code.UNAVAILABLE, "图库暂不可用"));
        mvc.perform(get(base))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.references").isEmpty());
    }
}
