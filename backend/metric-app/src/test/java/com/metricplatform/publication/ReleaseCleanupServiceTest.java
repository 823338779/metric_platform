package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos;
import com.metricplatform.persistence.store.QueryStore;
import com.metricplatform.persistence.store.ReleaseStore;
import com.metricplatform.query.QueryService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "metric-platform.dbt-runtime.enabled=true")
@ActiveProfiles("test")
class ReleaseCleanupServiceTest {
    @Autowired ReleaseCleanupService cleanup;
    @Autowired ReleaseStore releases;
    @Autowired QueryStore queries;
    @Autowired QueryService queryService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EngineClient runtime;
    private String projectId;

    @BeforeEach
    void setup() {
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", "profile");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    private UUID release(char sha, char digest, UUID runId, String state) {
        UUID id =
                releases.observe(
                                projectId,
                                new ProjectRevision(
                                        String.valueOf(sha).repeat(40),
                                        String.valueOf(digest).repeat(64)),
                                "c")
                        .releaseId();
        jdbc.update(
                "update semantic_release set state=?,runtime_run_id=?,published_at=case when ?='PUBLISHED' then current_timestamp else null end where release_id=?",
                state,
                runId,
                state,
                id);
        return id;
    }

    private String cleanupState(UUID id) {
        return jdbc.queryForObject(
                "select cleanup_state from semantic_release where release_id=?", String.class, id);
    }

    @Test
    void protectsActivePreviousAndInFlightQueries() {
        UUID oldRun = UUID.randomUUID(),
                previousRun = UUID.randomUUID(),
                activeRun = UUID.randomUUID();
        UUID old = release('a', '1', oldRun, "PUBLISHED");
        UUID previous = release('b', '2', previousRun, "PUBLISHED");
        UUID active = release('c', '3', activeRun, "PUBLISHED");
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?",
                active,
                projectId);
        queries.reserve(UUID.randomUUID(), projectId, old, UUID.randomUUID());
        assertThat(cleanup.cleanupEligible(projectId)).isZero();
        verify(runtime, never()).cleanup(any());
        assertThat(cleanupState(old)).isEqualTo("PENDING");
        assertThat(cleanupState(previous)).isEqualTo("PENDING");
        assertThat(cleanupState(active)).isEqualTo("PENDING");
        when(runtime.getQueryByKey(any()))
                .thenReturn(
                        new EngineDtos.QuerySnapshot(
                                UUID.randomUUID(), "COMPLETED", null, null, null));
        queryService.reconcilePending();
        assertThat(queries.countNonTerminal(old)).isZero();
        assertThat(cleanup.cleanupEligible(projectId)).isEqualTo(1);
        verify(runtime).cleanup(oldRun);
    }

    @Test
    void sharedRunIsProtectedByActiveReleaseAndCleansAsOneGroupLater() {
        UUID shared = UUID.randomUUID();
        UUID a = release('a', '1', shared, "SUPERSEDED");
        UUID b = release('b', '1', shared, "PUBLISHED");
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?", b, projectId);
        assertThat(cleanup.cleanupEligible(projectId)).isZero();
        assertThat(cleanupState(a)).isEqualTo("PENDING");
        UUID c = release('c', '2', UUID.randomUUID(), "PUBLISHED");
        UUID d = release('d', '3', UUID.randomUUID(), "PUBLISHED");
        jdbc.update(
                "update semantic_project set active_release_id=? where project_id=?", d, projectId);
        assertThat(cleanup.cleanupEligible(projectId)).isEqualTo(1);
        verify(runtime).cleanup(shared);
        assertThat(cleanupState(a)).isEqualTo("CLEANED");
        assertThat(cleanupState(b)).isEqualTo("CLEANED");
        assertThat(cleanupState(c)).isEqualTo("PENDING");
    }

    @Test
    void failedCleanupRetainsMetadataAndCanRetry() {
        UUID failedRun = UUID.randomUUID();
        UUID failed = release('a', '1', failedRun, "FAILED");
        doThrow(new EngineClient.ServiceFailure(true, 503))
                .doNothing()
                .when(runtime)
                .cleanup(failedRun);
        assertThat(cleanup.cleanupEligible(projectId)).isZero();
        assertThat(cleanupState(failed)).isEqualTo("PENDING");
        assertThat(cleanup.cleanupEligible(projectId)).isEqualTo(1);
        assertThat(cleanupState(failed)).isEqualTo("CLEANED");
    }
}
