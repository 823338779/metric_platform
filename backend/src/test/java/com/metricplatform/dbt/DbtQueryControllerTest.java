package com.metricplatform.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.dbt.DbtRuntimeDtos.*;
import com.metricplatform.gitsync.DbtProjectRevision;
import com.metricplatform.identity.PlatformAuthenticationService;
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
class DbtQueryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DbtReleaseStore releases;
    @MockitoBean DbtRuntimeClient runtime;
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
                                new DbtProjectRevision("a".repeat(40), "1".repeat(64)),
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
        assertThat(
                        jdbc.queryForObject(
                                "select release_id from semantic_query where query_id=?",
                                UUID.class,
                                queryId))
                .isEqualTo(releaseId);
        UUID newRelease =
                releases.observe(
                                projectId,
                                new DbtProjectRevision("b".repeat(40), "2".repeat(64)),
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
    void synchronousMetricFlowRejectionUses422Problem() throws Exception {
        when(runtime.submitQuery(any())).thenThrow(new DbtRuntimeClient.ServiceFailure(false, 422));
        String payload = "{\"releaseId\":\"" + releaseId + "\",\"metrics\":[\"unknown\"]}";
        mvc.perform(
                        post("/api/v1/semantic-projects/{projectId}/queries", projectId)
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DBT_QUERY_REJECTED"));
    }
}
