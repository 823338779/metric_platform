package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.CatalogReadRepository;
import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos.*;
import com.metricplatform.engine.dbt.git.DbtProjectReader;
import com.metricplatform.gitsync.GitSyncProperties;
import com.metricplatform.persistence.publication.PublicationStore;
import com.metricplatform.persistence.store.ReleaseStore;
import com.metricplatform.query.QueryDtos.*;
import com.metricplatform.query.QueryRepository;
import com.metricplatform.query.QueryService;
import com.metricplatform.query.ReleaseGoneException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 使用真实临时 Git main 和 PostgreSQL 串起平台流程；运行服务仅由无凭据桩模拟。 */
@SpringBootTest(properties = "metric-platform.dbt-runtime.enabled=true")
@ActiveProfiles("test")
class PublicationFlowTest {
    @TempDir Path root;
    @Autowired ReleaseStore releases;
    @Autowired PublicationStore publication;
    @Autowired CatalogReadRepository catalog;
    @Autowired QueryService queries;
    @Autowired QueryRepository queryReferences;
    @Autowired ReleaseCleanupService cleanup;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EngineClient runtime;
    private String projectId;
    private final Map<UUID, RunRequest> runs = new HashMap<>();
    private static final String PROJECT = "project", MODEL = "project/models/orders.yml";

    @AfterEach
    void tearDown() {
        if (projectId != null)
            jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    @Test
    void gitPushBuildsEveryChangedDefinitionAndRetainsPinnedQueryUntilCleanup() throws Exception {
        Path remote = Files.createDirectory(root.resolve("remote"));
        git(remote, "init", "-b", "main");
        git(remote, "config", "user.name", "Test");
        git(remote, "config", "user.email", "test@example.invalid");
        Files.createDirectories(remote.resolve("project/models"));
        Files.writeString(
                remote.resolve("project/dbt_project.yml"),
                "name: sales\r\nversion: '1.0'\r\nconfig-version: 2\r\n");
        Files.writeString(remote.resolve(MODEL), "models: []\r\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "first");

        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        GitSyncProperties settings = new GitSyncProperties();
        settings.setRepositoryId("repo");
        settings.setProjectId(projectId);
        settings.setProjectSubdir(PROJECT);
        settings.setProfileBindingId("profile");
        settings.setConfigVersion("config-1");
        settings.setToolchainVersion("dbt-1/mf-1");
        DbtProjectReader reader =
                new DbtProjectReader(
                        "git", remote.toString(), root.resolve("mirror"), Duration.ofSeconds(10));
        ReleaseCoordinator coordinator =
                new ReleaseCoordinator(reader, releases, publication, runtime, settings);
        when(runtime.submitRun(any()))
                .thenAnswer(
                        call -> {
                            RunRequest request = call.getArgument(0);
                            UUID id = UUID.randomUUID();
                            runs.put(id, request);
                            return new RunReceipt(id);
                        });
        when(runtime.getRun(any()))
                .thenAnswer(
                        call -> {
                            UUID id = call.getArgument(0);
                            RunRequest request = runs.get(id);
                            return new RunSnapshot(
                                    id,
                                    "READY",
                                    request.commitSha(),
                                    request.projectDigest(),
                                    "profile",
                                    "config-1",
                                    "dbt-1/mf-1",
                                    "release_" + id.toString().replace("-", ""),
                                    "m".repeat(64),
                                    "s".repeat(64),
                                    "r".repeat(64),
                                    true,
                                    true,
                                    true,
                                    true);
                        });
        when(runtime.getCatalog(any()))
                .thenReturn(
                        new CatalogSnapshot(
                                List.of(
                                        new CatalogResource(
                                                "metric.sales.revenue",
                                                "metric.sales.revenue",
                                                "metric",
                                                "revenue",
                                                "Revenue",
                                                MODEL,
                                                new ObjectMapper().createObjectNode(),
                                                true)),
                                List.of()));
        UUID job = UUID.randomUUID();
        when(runtime.submitQuery(any())).thenReturn(new QueryReceipt(job));
        when(runtime.getQuery(job))
                .thenReturn(new QuerySnapshot(job, "COMPLETED", null, null, null));

        coordinator.refresh();
        UUID first = releases.active(projectId);
        assertThat(first).isNotNull();
        assertThat(catalog.catalog(projectId, first, null, null, 0, 20).resources()).hasSize(1);
        assertThat(catalog.catalog(projectId, first, null, "METRIC", 0, 20).total()).isEqualTo(1);
        assertThat(catalog.catalog(projectId, first, null, "TABLE", 0, 20).total()).isZero();
        UUID pinned =
                queries.submit(
                                projectId,
                                first,
                                QueryMode.QUERY,
                                new TypedQuery(first, List.of("revenue"), List.of(), List.of(), 10))
                        .queryId();
        assertThat(queries.get(projectId, pinned).releaseId()).isEqualTo(first);

        Files.writeString(remote.resolve(MODEL), "models:\r\n  - name: orders\r\n");
        git(remote, "commit", "-am", "second");
        coordinator.refresh();
        UUID second = releases.active(projectId);
        assertThat(second).isNotEqualTo(first);
        assertThat(runs).hasSize(2);
        assertThatThrownBy(() -> catalog.catalog(projectId, first, null, null, 0, 20))
                .isInstanceOf(ReleaseGoneException.class);
        assertThatThrownBy(
                        () ->
                                queries.submit(
                                        projectId,
                                        first,
                                        QueryMode.QUERY,
                                        new TypedQuery(
                                                first,
                                                List.of("revenue"),
                                                List.of(),
                                                List.of(),
                                                10)))
                .isInstanceOf(ReleaseGoneException.class);
        assertThatThrownBy(
                        () ->
                                queryReferences.reserve(
                                        UUID.randomUUID(),
                                        projectId,
                                        first,
                                        UUID.randomUUID(),
                                        "QUERY"))
                .isInstanceOf(ReleaseGoneException.class);
        assertThat(catalog.release(projectId, first).state()).isEqualTo("PUBLISHED");
        Files.writeString(remote.resolve("project/README.md"), "notes\r\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "readme");
        coordinator.refresh();
        assertThat(releases.active(projectId)).isEqualTo(second);
        assertThat(runs).hasSize(2);

        Files.writeString(
                remote.resolve(MODEL),
                "models:\r\n  - name: orders\r\n    description: changed\r\n");
        git(remote, "commit", "-am", "third");
        coordinator.refresh();
        UUID third = releases.active(projectId);
        assertThat(third).isNotEqualTo(second);
        assertThat(runs).hasSize(3);
        assertThat(queries.get(projectId, pinned).releaseId()).isEqualTo(first);
        assertThat(cleanup.cleanupEligible(projectId)).isEqualTo(2);
        assertThat(catalog.release(projectId, first).cleanupState()).isEqualTo("CLEANED");
        assertThat(catalog.release(projectId, second).cleanupState()).isEqualTo("CLEANED");
    }

    private void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process =
                new ProcessBuilder(command)
                        .directory(cwd.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
