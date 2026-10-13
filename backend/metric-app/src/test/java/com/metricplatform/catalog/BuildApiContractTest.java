package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuildApiContractTest {
    private static final java.util.UUID ENVIRONMENT_ID =
            java.util.UUID.fromString("12345678-1234-4234-8234-000000000001");

    @Autowired MockMvc mvc;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    @Autowired JdbcTemplate jdbc;
    @MockitoBean BuildGateway gateway;
    @MockitoBean EnvironmentBuildGateway environmentBuilds;
    @MockitoBean PlatformAuthenticationService authentication;
    private static final String ROOT = "/api/dbt", TOKEN = "test-v3";
    private final UUID build = UUID.randomUUID(), user = UUID.randomUUID();

    @BeforeEach
    void login() {
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(user, "test", "Test", null, 1));
    }

    @Test
    void historyRequiresRepositoryAndForwardsCursorToLiveService() throws Exception {
        mvc.perform(get(ROOT + "/builds").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest());
        var filter =
                new BuildFilter(ENVIRONMENT_ID, "repo-a", "feature/a", null, "service-cursor", 10);
        when(gateway.listBuilds(filter)).thenReturn(new BuildPage(List.of(), "next-cursor"));
        mvc.perform(
                        get(ROOT + "/builds")
                                .param("repository", "repo-a")
                                .param("environmentId", ENVIRONMENT_ID.toString())
                                .param("branchName", "feature/a")
                                .param("cursor", "service-cursor")
                                .param("limit", "10")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
        verify(gateway).listBuilds(filter);
    }

    @Test
    void bffPreservesBuildAndBusinessError() throws Exception {
        when(gateway.catalog(eq(build), any())).thenReturn(new CatalogPage(List.of(), null, build));
        mvc.perform(
                        get(ROOT + "/builds/" + build + "/catalog")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()));
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
        var actual = new java.util.TreeSet<String>();
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
        var declared = new java.util.TreeSet<String>();
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
                        List.of("metric.revenue"),
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
                                new com.metricplatform.environment.EnvironmentContracts
                                        .ExecutionSnapshot(
                                        ENVIRONMENT_ID,
                                        1,
                                        new com.metricplatform.environment.EnvironmentContracts
                                                .ConnectionTarget(
                                                "starrocks", "warehouse", "localhost", 9030, null),
                                        "warehouse-test",
                                        "default_catalog",
                                        "analytics",
                                        2,
                                        "Asia/Shanghai"),
                                "1",
                                "1",
                                BuildStatus.SUCCEEDED,
                                "DONE",
                                null,
                                null,
                                null,
                                false,
                                true,
                                true,
                                null,
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
                                "metricflow_granularity_alignment",
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
        assertThat(
                        jdbc.queryForObject(
                                "select user_id from query_execution_audit where build_id=?",
                                UUID.class,
                                build))
                .isEqualTo(user);
    }
}
