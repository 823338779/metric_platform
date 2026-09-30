package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos.*;
import com.metricplatform.gitsync.GitSyncProperties;
import com.metricplatform.persistence.publication.PublicationStore;
import com.metricplatform.persistence.store.ReleaseStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReleaseCoordinatorTest {
    @Autowired ReleaseStore releases;
    @Autowired PublicationStore publication;
    @Autowired JdbcTemplate jdbc;
    private GitHeadGateway reader;
    private EngineClient runtime;
    private GitSyncProperties properties;
    private AtomicReference<ProjectRevision> head;
    private AtomicReference<String> runState;
    private UUID runId;
    private String projectId;

    @BeforeEach
    void setup() {
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        reader = mock(GitHeadGateway.class);
        runtime = mock(EngineClient.class);
        properties = new GitSyncProperties();
        properties.setRepositoryId("repo");
        properties.setProjectId(projectId);
        properties.setProjectSubdir("project");
        properties.setProfileBindingId("starrocks-profile");
        properties.setConfigVersion("config-1");
        properties.setToolchainVersion("dbt-1/mf-1");
        head = new AtomicReference<>(revision('a', '1'));
        runState = new AtomicReference<>("BUILDING");
        runId = UUID.randomUUID();
        when(reader.readRevision("project")).thenAnswer(call -> head.get());
        when(runtime.submitRun(any())).thenReturn(new RunReceipt(runId));
        when(runtime.getRun(runId)).thenAnswer(call -> snapshot(runId, runState.get(), 'a', '1'));
        when(runtime.getRunByKey(any()))
                .thenAnswer(call -> snapshot(runId, runState.get(), 'a', '1'));
        when(runtime.getCatalog(runId)).thenReturn(catalog());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    @Test
    void readmeOnlyCommitBuildsNewRunAndPublishesLatestSha() {
        var coordinator = coordinator();
        coordinator.refresh();
        head.set(revision('b', '1'));
        UUID secondRun = UUID.randomUUID();
        when(runtime.submitRun(any())).thenReturn(new RunReceipt(secondRun));
        when(runtime.getRun(secondRun)).thenReturn(snapshot(secondRun, "READY", 'b', '1'));
        when(runtime.getCatalog(secondRun)).thenReturn(catalog());
        coordinator.refresh();

        UUID activeId = releases.active(projectId);
        assertThat(activeId).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select target_commit_sha from semantic_release where release_id=?",
                                String.class,
                                activeId))
                .isEqualTo("b".repeat(40));
        assertThat(
                        jdbc.queryForObject(
                                "select build_source_sha from semantic_release where release_id=?",
                                String.class,
                                activeId))
                .isEqualTo("b".repeat(40));
        assertThat(releases.work(activeId).runId()).isEqualTo(secondRun);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(2);
    }

    @Test
    void changedProjectStartsNewFullRunAndSupersedesOldCandidate() {
        var coordinator = coordinator();
        coordinator.refresh();
        head.set(revision('b', '2'));
        UUID secondRun = UUID.randomUUID();
        when(runtime.submitRun(any()))
                .thenAnswer(
                        call ->
                                new RunReceipt(
                                        call.<RunRequest>getArgument(0).commitSha().startsWith("b")
                                                ? secondRun
                                                : runId));
        when(runtime.getRun(secondRun)).thenReturn(snapshot(secondRun, "READY", 'b', '2'));
        when(runtime.getCatalog(secondRun)).thenReturn(catalog());
        coordinator.refresh();

        assertThat(releases.active(projectId)).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=? and state='SUPERSEDED'",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select runtime_run_id from semantic_release where release_id=?",
                                UUID.class,
                                releases.active(projectId)))
                .isEqualTo(secondRun);
    }

    @Test
    void restartResumesPendingRunWithoutCreatingNewCandidate() {
        coordinator().refresh();
        runState.set("READY");
        coordinator().resumePending();

        assertThat(releases.active(projectId)).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
    }

    @Test
    void readmeChangeAfterFailureSubmitsNewRun() {
        var coordinator = coordinator();
        when(runtime.getRun(runId)).thenReturn(snapshot(runId, "FAILED", 'a', '1'));
        coordinator.refresh();
        head.set(revision('b', '1'));
        UUID secondRun = UUID.randomUUID();
        when(runtime.submitRun(any())).thenReturn(new RunReceipt(secondRun));
        when(runtime.getRun(secondRun)).thenReturn(snapshot(secondRun, "READY", 'b', '1'));
        when(runtime.getCatalog(secondRun)).thenReturn(catalog());
        coordinator.refresh();

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(2);
        verify(runtime, times(2)).submitRun(any());
        assertThat(releases.work(releases.active(projectId)).runId()).isEqualTo(secondRun);
    }

    @Test
    void submissionTimeoutFindsRunByPersistedIdempotencyKey() {
        when(runtime.submitRun(any())).thenThrow(new EngineClient.ServiceFailure(true, 0));
        runState.set("READY");

        coordinator().refresh();

        assertThat(releases.active(projectId)).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
    }

    @Test
    void repeatedTransientCatalogFailureStopsAfterThreeAttempts() {
        runState.set("READY");
        when(runtime.getCatalog(runId)).thenThrow(new EngineClient.ServiceFailure(true, 503));
        var coordinator = coordinator();
        coordinator.refresh();
        coordinator.refresh();
        assertThat(
                        jdbc.queryForObject(
                                "select transient_retry_count from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
        jdbc.update(
                "update semantic_release set retry_after=current_timestamp - interval '1 second' where project_id=?",
                projectId);
        coordinator.refresh();
        jdbc.update(
                "update semantic_release set retry_after=current_timestamp - interval '1 second' where project_id=?",
                projectId);
        coordinator.refresh();

        assertThat(
                        jdbc.queryForObject(
                                "select state from semantic_release where project_id=?",
                                String.class,
                                projectId))
                .isEqualTo("FAILED");
        assertThat(releases.active(projectId)).isNull();
    }

    @Test
    void fixedSchemaPublishesSecondRun() {
        properties.setSchemaName("dbt_ecom");
        runState.set("READY");
        when(runtime.getRun(runId)).thenReturn(snapshotWithSchema(runId, 'a', '1', "dbt_ecom"));
        var coordinator = coordinator();
        coordinator.refresh();
        UUID first = releases.active(projectId);
        UUID nextRun = UUID.randomUUID();
        head.set(revision('b', '2'));
        when(runtime.submitRun(any())).thenReturn(new RunReceipt(nextRun));
        when(runtime.getRun(nextRun)).thenReturn(snapshotWithSchema(nextRun, 'b', '2', "dbt_ecom"));
        when(runtime.getCatalog(nextRun)).thenReturn(catalog());

        coordinator.refresh();

        assertThat(releases.active(projectId)).isNotEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=? and state='PUBLISHED'",
                                Integer.class,
                                projectId))
                .isEqualTo(2);
    }

    @Test
    void wrongSchemaKeepsActiveRelease() {
        properties.setSchemaName("dbt_ecom");
        runState.set("READY");
        when(runtime.getRun(runId)).thenReturn(snapshotWithSchema(runId, 'a', '1', "dbt_ecom"));
        var coordinator = coordinator();
        coordinator.refresh();
        UUID first = releases.active(projectId);
        UUID nextRun = UUID.randomUUID();
        head.set(revision('b', '2'));
        when(runtime.submitRun(any())).thenReturn(new RunReceipt(nextRun));
        when(runtime.getRun(nextRun))
                .thenReturn(snapshotWithSchema(nextRun, 'b', '2', "run_unexpected"));
        when(runtime.getCatalog(nextRun)).thenReturn(catalog());

        coordinator.refresh();

        assertThat(releases.active(projectId)).isEqualTo(first);
    }

    @Test
    void fixedSchemaRejectsReusedPhysicalRun() {
        properties.setSchemaName("dbt_ecom");
        runState.set("READY");
        when(runtime.getRun(runId)).thenReturn(snapshotWithSchema(runId, 'a', '1', "dbt_ecom"));
        var coordinator = coordinator();
        coordinator.refresh();
        UUID first = releases.active(projectId);
        head.set(revision('b', '2'));
        when(runtime.getRun(runId)).thenReturn(snapshotWithSchema(runId, 'b', '2', "dbt_ecom"));

        coordinator.refresh();

        assertThat(releases.active(projectId)).isEqualTo(first);
    }

    private ReleaseCoordinator coordinator() {
        return new ReleaseCoordinator(reader, releases, publication, runtime, properties);
    }

    private static RunSnapshot snapshot(UUID id, String state, char sha, char digest) {
        return new RunSnapshot(
                id,
                state,
                String.valueOf(sha).repeat(40),
                String.valueOf(digest).repeat(64),
                "starrocks-profile",
                "config-1",
                "dbt-1/mf-1",
                "release_" + sha,
                "m".repeat(64),
                "s".repeat(64),
                "r".repeat(64),
                true,
                true,
                true,
                true);
    }

    private static RunSnapshot snapshotWithSchema(UUID id, char sha, char digest, String schema) {
        return new RunSnapshot(
                id,
                "READY",
                String.valueOf(sha).repeat(40),
                String.valueOf(digest).repeat(64),
                "starrocks-profile",
                "config-1",
                "dbt-1/mf-1",
                schema,
                "m".repeat(64),
                "s".repeat(64),
                "r".repeat(64),
                true,
                true,
                true,
                true);
    }

    private static CatalogSnapshot catalog() {
        return new CatalogSnapshot(
                List.of(
                        new CatalogResource(
                                "metric.sales.revenue",
                                "metric.sales.revenue",
                                "metric",
                                "revenue",
                                "Revenue",
                                "project/models/metrics.yml",
                                new ObjectMapper().createObjectNode(),
                                true)),
                List.of());
    }

    private static ProjectRevision revision(char sha, char digest) {
        return new ProjectRevision(
                String.valueOf(sha).repeat(40), String.valueOf(digest).repeat(64));
    }
}
