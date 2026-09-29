package com.metricplatform.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos.*;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.persistence.store.ReleaseStore;
import com.metricplatform.publication.ProjectRevision;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "metric-platform.dbt-runtime.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReleaseStore releases;
    @MockitoBean EngineClient runtime;
    @MockitoBean PlatformAuthenticationService authentication;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private String projectId;
    private UUID releaseId;
    private UUID runId;
    private UUID queryJobId;
    private static final String TOKEN = "dbt-query-token";

    @BeforeEach
    void setup() {
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", "starrocks-profile");
        releaseId =
                releases.observe(
                                projectId,
                                new ProjectRevision("a".repeat(40), "1".repeat(64)),
                                "config-1")
                        .releaseId();
        runId = UUID.randomUUID();
        queryJobId = UUID.randomUUID();
        jdbc.update(
                "update semantic_release set state='PUBLISHED',runtime_run_id=? where release_id=?",
                runId,
                releaseId);
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?",
                releaseId,
                projectId);
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        when(runtime.submitQuery(any())).thenReturn(new QueryReceipt(queryJobId));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    @Test
    void queryPinsReleaseBeforeSwitchAndPreservesExactNumericValues() throws Exception {
        String payload =
                "{\"releaseId\":\""
                        + releaseId
                        + "\",\"metrics\":[\"revenue\"],\"groupBy\":[],\"filters\":[],\"limit\":10}";
        String body =
                mvc.perform(
                                post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID queryId = UUID.fromString(mapper.readTree(body).path("queryId").asText());
        verify(runtime)
                .submitQuery(argThat(job -> job.orderBy() != null && job.orderBy().isEmpty()));
        assertThat(
                        jdbc.queryForObject(
                                "select release_id from semantic_query where query_id=?",
                                UUID.class,
                                queryId))
                .isEqualTo(releaseId);
        UUID newRelease =
                releases.observe(
                                projectId,
                                new ProjectRevision("b".repeat(40), "2".repeat(64)),
                                "config-1")
                        .releaseId();
        jdbc.update("update semantic_release set state='PUBLISHED' where release_id=?", newRelease);
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?",
                newRelease,
                projectId);
        when(runtime.getQuery(queryJobId))
                .thenReturn(
                        new QuerySnapshot(
                                queryJobId,
                                "COMPLETED",
                                mapper.readTree(
                                        "[{\"name\":\"revenue\",\"type\":\"DECIMAL(38,2)\"}]"),
                                mapper.readTree("[[12345678901234567890.12]]"),
                                null));

        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        projectId,
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                .andExpect(jsonPath("$.rows[0][0]").value("12345678901234567890.12"));
        jdbc.update(
                "update semantic_release set cleanup_state='CLEANED' where release_id=?",
                releaseId);
        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        projectId,
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isGone());
    }

    @Test
    void previewRejectsSqlAndExcessiveLimit() throws Exception {
        String prefix =
                "/api/v1/semantic-projects/" + projectId + "/datasets/model.sales.orders:preview";
        mvc.perform(
                        post(prefix)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"releaseId\":\""
                                                + releaseId
                                                + "\",\"sql\":\"select * from x\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(
                        post(prefix)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"releaseId\":\"" + releaseId + "\",\"limit\":10001}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cleanedOrCrossProjectReleaseCannotStartQuery() throws Exception {
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"revenue\"]}";
        mvc.perform(
                        post("/api/v1/semantic-projects/{projectId}/queries", "another-project")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isNotFound());
        jdbc.update(
                "update semantic_release set cleanup_state='CLEANED' where release_id=?",
                releaseId);
        mvc.perform(
                        post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isGone());
    }

    @Test
    void invalidMetricCombinationReturnsStructuredAsyncDiagnostic() throws Exception {
        String payload =
                "{\"releaseId\":\""
                        + releaseId
                        + "\",\"metrics\":[\"revenue\"],\"groupBy\":[\"unknown_dimension\"]}";
        String body =
                mvc.perform(
                                post(
                                                "/api/v1/semantic-projects/{projectId}/queries:explain",
                                                projectId)
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID queryId = UUID.fromString(mapper.readTree(body).path("queryId").asText());
        when(runtime.getQuery(queryJobId))
                .thenReturn(
                        new QuerySnapshot(
                                queryJobId, "FAILED", null, null, "INVALID_DIMENSION_COMBINATION"));

        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        projectId,
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DIMENSION_COMBINATION"));
    }

    @Test
    void explainResultPreservesSqlFromRuntimeResponse() throws Exception {
        String sql = "select count(distinct order_id) as order_count from orders";
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"order_count\"]}";
        String body =
                mvc.perform(
                                post(
                                                "/api/v1/semantic-projects/{projectId}/queries:explain",
                                                projectId)
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID queryId = UUID.fromString(mapper.readTree(body).path("queryId").asText());
        var response =
                mapper.createObjectNode()
                        .put("queryId", queryJobId.toString())
                        .put("state", "READY")
                        .put("sql", sql);
        response.putArray("columns");
        response.putArray("rows");
        when(runtime.getQuery(queryJobId))
                .thenReturn(mapper.treeToValue(response, QuerySnapshot.class));

        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        projectId,
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("EXPLAIN"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sql").value(sql));
    }

    @Test
    void synchronousMetricFlowRejectionUses422Problem() throws Exception {
        when(runtime.submitQuery(any())).thenThrow(new EngineClient.ServiceFailure(false, 422));
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"unknown\"]}";
        mvc.perform(
                        post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DBT_QUERY_REJECTED"));
    }

    @Test
    void multiMetricOptionsAreFixedToRelease() throws Exception {
        when(runtime.queryOptions(runId, java.util.List.of("revenue", "orders")))
                .thenReturn(
                        mapper.readTree(
                                "{\"metrics\":[{\"name\":\"revenue\"},{\"name\":\"orders\"}],\"dimensions\":[],\"timeDimensions\":[{\"token\":\"metric_time__month\",\"granularity\":\"month\"}],\"allowedFilters\":[\"=\"]}"));
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/query-options", projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .param("releaseId", releaseId.toString())
                                .param("metrics", "revenue", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                .andExpect(jsonPath("$.timeDimensions[0].token").value("metric_time__month"));
        verify(runtime).queryOptions(runId, java.util.List.of("revenue", "orders"));
    }

    @Test
    void dimensionValuesHoldReleaseReference() throws Exception {
        String payload =
                "{\"releaseId\":\""
                        + releaseId
                        + "\",\"metrics\":[\"revenue\"],\"dimension\":\"region\",\"limit\":10}";
        mvc.perform(
                        post(
                                        "/api/v1/semantic-projects/{projectId}/queries:dimension-values",
                                        projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_query where release_id=? and"
                                        + " mode='DIMENSION_VALUES'",
                                Integer.class,
                                releaseId))
                .isEqualTo(1);
        verify(runtime)
                .submitQuery(
                        argThat(
                                job ->
                                        job.runId().equals(runId)
                                                && "region".equals(job.dimension())));
    }

    @Test
    void unsupportedOperatorAndOversizeDimensionValueLimitAreRejected() throws Exception {
        String route = "/api/v1/semantic-projects/{projectId}/queries";
        mvc.perform(
                        post(route, projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"releaseId\":\""
                                                + releaseId
                                                + "\",\"metrics\":[\"revenue\"],\"filters\":[{\"field\":\"region\",\"operator\":\"LIKE\",\"value\":\"A\"}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(
                        post(
                                        "/api/v1/semantic-projects/{projectId}/queries:dimension-values",
                                        projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"releaseId\":\""
                                                + releaseId
                                                + "\",\"metrics\":[\"revenue\"],\"dimension\":\"region\",\"limit\":10001}"))
                .andExpect(status().isUnprocessableEntity());
        verify(runtime, never()).submitQuery(any());
    }

    @Test
    void anotherProjectCannotReadQueryStatus() throws Exception {
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"revenue\"]}";
        String body =
                mvc.perform(
                                post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID queryId = UUID.fromString(mapper.readTree(body).path("queryId").asText());
        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        "another-project",
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        verify(runtime, never()).getQuery(any());
    }

    @Test
    void readyRuntimeQueryIsCompletedForPlatformClients() throws Exception {
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"revenue\"]}";
        String body =
                mvc.perform(
                                post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID queryId = UUID.fromString(mapper.readTree(body).path("queryId").asText());
        when(runtime.getQuery(queryJobId))
                .thenReturn(
                        new QuerySnapshot(
                                queryJobId,
                                "READY",
                                mapper.readTree("[]"),
                                mapper.readTree("[]"),
                                null));
        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/queries/{queryId}",
                                        projectId,
                                        queryId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
