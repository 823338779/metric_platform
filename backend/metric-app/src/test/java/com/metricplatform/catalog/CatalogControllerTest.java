package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.persistence.store.CatalogStore;
import com.metricplatform.persistence.store.ReleaseStore;
import com.metricplatform.publication.ProjectRevision;
import com.metricplatform.security.PlatformPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReleaseStore releases;
    @Autowired CatalogStore catalog;
    @MockitoBean PlatformAuthenticationService authentication;
    private String projectId;
    private UUID releaseId;
    private static final String TOKEN = "dbt-catalog-token";

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
        jdbc.update("update semantic_release set state='PUBLISHED' where release_id=?", releaseId);
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?",
                releaseId,
                projectId);
        catalog.replace(
                releaseId,
                List.of(
                        new CatalogStore.Resource(
                                "metric.sales.revenue",
                                "metric.sales.revenue",
                                "metric",
                                "revenue",
                                "Revenue",
                                "project/models/metrics.yml",
                                "{}",
                                true)),
                List.of());
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    @Test
    void projectStatusIncludesObservationTimeForRefreshTracking() throws Exception {
        mvc.perform(get("/api/v1/semantic-projects").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.projectId == '" + projectId + "')].lastObservedAt")
                                .isNotEmpty());
    }

    @Test
    void requiresBearerAndListsOnlyPublishedCatalog() throws Exception {
        mvc.perform(get("/api/v1/semantic-projects/{projectId}/catalog", projectId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/semantic-projects").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").exists());
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/catalog", projectId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                .andExpect(jsonPath("$.resources[0].resourceId").value("metric.sales.revenue"));
        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/resources/{resourceId}",
                                        projectId,
                                        "metric.sales.revenue")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsCandidateAndCrossProjectReleaseForCatalog() throws Exception {
        UUID candidate =
                releases.observe(
                                projectId,
                                new ProjectRevision("b".repeat(40), "2".repeat(64)),
                                "config-1")
                        .releaseId();
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/catalog", projectId)
                                .param("releaseId", candidate.toString())
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/catalog", "another-project")
                                .param("releaseId", releaseId.toString())
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/releases", projectId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cleanedReleaseKeepsHistoryButCatalogReturnsGone() throws Exception {
        jdbc.update(
                "update semantic_release set cleanup_state='CLEANED' where release_id=?",
                releaseId);
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/catalog", projectId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isGone());
        mvc.perform(
                        get(
                                        "/api/v1/semantic-projects/{projectId}/releases/{releaseId}",
                                        projectId,
                                        releaseId)
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleanupState").value("CLEANED"));
    }

    @Test
    void capsCatalogPageSize() throws Exception {
        mvc.perform(
                        get("/api/v1/semantic-projects/{projectId}/catalog", projectId)
                                .param("size", "1001")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void presentationUsesPublishedArtifactVersionsAndRejectsCleanedRelease() throws Exception {
        String validation =
                "{\"artifactSchemas\":{\"manifest\":\"https://schemas.getdbt.com/dbt/manifest/v12.json\",\"semanticManifest\":\"dbt-metricflow/0.15.0\",\"catalog\":\"https://schemas.getdbt.com/dbt/catalog/v1.json\"}}";
        jdbc.update(
                "update semantic_release set validation_json=?::jsonb where release_id=?",
                validation,
                releaseId);
        String path = "/api/v1/semantic-projects/{projectId}/resources/{resourceId}/presentation";
        mvc.perform(
                        get(path, projectId, "metric.sales.revenue")
                                .param("releaseId", releaseId.toString())
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId.toString()))
                .andExpect(jsonPath("$.nativeDetails").exists());
        jdbc.update(
                "update semantic_release set cleanup_state='CLEANED' where release_id=?",
                releaseId);
        mvc.perform(
                        get(path, projectId, "metric.sales.revenue")
                                .param("releaseId", releaseId.toString())
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isGone());
    }
}
