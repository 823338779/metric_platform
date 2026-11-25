package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.environment.EnvironmentContracts;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import java.util.*;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuildApiContractTest {
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("12345678-1234-4234-8234-000000000001");

    @Autowired MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired TenantFixtureMapper tenants;
    @Autowired CatalogFixtureMapper fixtures;
    @MockitoBean BuildGateway gateway;
    @MockitoBean EnvironmentBuildGateway environmentBuilds;
    @MockitoBean PlatformAuthenticationService authentication;
    private static final String ROOT = "/api/dbt", TOKEN = "test-v3";
    private final UUID build = UUID.randomUUID();

    private UUID user;

    @BeforeEach
    void login() {
        var manager = TenantFixture.bind(tenants, ENVIRONMENT_ID, build);
        user = manager.identityId();
        when(authentication.authenticate(TOKEN)).thenReturn(manager);
    }

    @Test
    void historyRequiresRepositoryAndForwardsCursorToLiveService() throws Exception {
        mvc.perform(get(ROOT + "/builds").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest());
        var filter =
                new BuildFilter(ENVIRONMENT_ID, "repo-a", "feature/a", null, "service-cursor", 10);
        var first = new BuildFilter(ENVIRONMENT_ID, "repo-a", "feature/a", null, null, 10);
        var visible = mock(BuildView.class);
        when(visible.buildId()).thenReturn(build);
        when(gateway.listBuilds(first))
                .thenReturn(new BuildPage(List.of(visible), "service-cursor"));
        when(gateway.listBuilds(filter)).thenReturn(new BuildPage(List.of(), null));
        String response =
                mvc.perform(
                                get(ROOT + "/builds")
                                        .param("repository", "repo-a")
                                        .param("environmentId", ENVIRONMENT_ID.toString())
                                        .param("branchName", "feature/a")
                                        .param("limit", "10")
                                        .header("Authorization", "Bearer " + TOKEN))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response)
                        .get("nextCursor")
                        .asText();
        assertThat(cursor).isNotEqualTo("service-cursor");
        mvc.perform(
                        get(ROOT + "/builds")
                                .param("repository", "another-repo")
                                .param("environmentId", ENVIRONMENT_ID.toString())
                                .param("cursor", cursor)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(
                        get(ROOT + "/builds")
                                .param("repository", "repo-a")
                                .param("environmentId", ENVIRONMENT_ID.toString())
                                .param("branchName", "feature/a")
                                .param("cursor", cursor)
                                .param("limit", "10")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(gateway).listBuilds(filter);
    }

    @Test
    void catalogSearchPreservesPageAndUpstreamErrors() throws Exception {
        String path = ROOT + "/builds/" + build + "/catalog/search";
        String resourceId = "metric.demo.sales";
        var summary = new ResourceSummary(resourceId, "sales", null, "销售额", "metric", false);
        var filter = new CatalogSearch(null, resourceId, "metric", null, 50);
        when(gateway.searchCatalog(build, filter))
                .thenReturn(new CatalogSearchPage(List.of(summary), null));
        mvc.perform(
                        get(path)
                                .param("resourceId", resourceId)
                                .param("resourceType", "metric")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resourceId").value(resourceId))
                .andExpect(jsonPath("$.items[0].queryNameUnique").value(false));

        var page = new CatalogSearch("销售", null, null, "other-build-cursor", 2);
        when(gateway.searchCatalog(build, page))
                .thenThrow(
                        new BuildGateway.Failure(
                                422,
                                new ErrorView(
                                        "INVALID_CURSOR", "invalid cursor", false, null, build)));
        mvc.perform(
                        get(path)
                                .param("keyword", "销售")
                                .param("cursor", "other-build-cursor")
                                .param("limit", "2")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        verify(gateway, never()).catalog(any());
    }

    @Test
    void bffPreservesBuildAndBusinessError() throws Exception {
        when(gateway.catalog(build))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(
                                        "[{\"unique_id\":\"model.shop.orders\",\"resource_type\":\"model\"}]"));
        mvc.perform(
                        get(ROOT + "/builds/" + build + "/catalog")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unique_id").value("model.shop.orders"));
        when(environmentBuilds.getBuild(build))
                .thenThrow(
                        new BuildGateway.Failure(
                                410,
                                new ErrorView(
                                        "PHYSICAL_OBJECTS_REMOVED",
                                        "removed",
                                        false,
                                        "QUERY",
                                        build)));
        mvc.perform(get(ROOT + "/builds/" + build).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("PHYSICAL_OBJECTS_REMOVED"))
                .andExpect(jsonPath("$.phase").value("QUERY"))
                .andExpect(jsonPath("$.buildId").value(build.toString()));
    }

    @Test
    void rejectsOldRequestFieldsWithoutCallingService() throws Exception {
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("application/json")
                                .content("{\"releaseId\":\"" + build + "\",\"mode\":\"QUERY\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(gateway);
    }

    @Test
    void removedAgentIntegrationRoutesAreUnavailable() throws Exception {
        for (var path : List.of(ROOT + "/projects", ROOT + "/projects/project/branches")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void runtimeContractMatchesRegisteredRoutes() throws Exception {
        var response =
                mvc.perform(get(ROOT + "/contract"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.protocolVersion").value("execution-snapshot"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        for (var parameter :
                document.path("paths").path(ROOT + "/builds").path("get").path("parameters")) {
            if (parameter.path("name").asText().equals("environmentId"))
                assertThat(parameter.path("required").asBoolean()).isTrue();
        }
        var actual = new TreeSet<String>();
        mappings.getHandlerMethods()
                .forEach(
                        (mapping, method) ->
                                mapping.getPatternValues().stream()
                                        .filter(path -> path.startsWith(ROOT))
                                        .forEach(
                                                path ->
                                                        actual.add(
                                                                path.replace(
                                                                        "{view:source|lineage|native-details}",
                                                                        "{view}"))));
        var declared = new TreeSet<String>();
        document.path("paths")
                .fieldNames()
                .forEachRemaining(
                        path -> {
                            if (path.startsWith(ROOT)) declared.add(path);
                        });
        assertThat(actual).isEqualTo(declared);
    }

    @Test
    void removedSemanticProjectRoutesAre404() throws Exception {
        for (var path :
                List.of(
                        "/api/v1/semantic-projects",
                        "/api/v2/semantic-projects",
                        "/api/v1/semantic-projects/p/releases",
                        "/api/v2/semantic-projects/p/branches"))
            mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isNotFound());
    }

    @Test
    void existingLoginAndQueryAttributionRemain() throws Exception {
        UUID query = UUID.randomUUID();
        var request =
                new QueryRequest(
                        QueryMode.QUERY,
                        List.of("revenue"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        100,
                        null,
                        null);
        when(gateway.getBuild(build))
                .thenReturn(
                        new BuildView(
                                build,
                                "repo",
                                "a",
                                null,
                                null,
                                new EnvironmentContracts.BuildExecutionSnapshot(
                                        ENVIRONMENT_ID,
                                        new EnvironmentContracts.ConnectionTarget(
                                                "starrocks", "warehouse", "localhost", 9030, null),
                                        "warehouse-test",
                                        "default_catalog",
                                        "analytics",
                                        2,
                                        "Asia/Shanghai"),
                                "1",
                                BuildStatus.SUCCEEDED,
                                "DONE",
                                null,
                                null,
                                null,
                                true,
                                null,
                                null));
        when(gateway.query(build, request))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.QUERY,
                                null,
                                "Asia/Shanghai",
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                false));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        var body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()));
        assertThat(fixtures.auditedUser(build)).isEqualTo(user);
    }
}
