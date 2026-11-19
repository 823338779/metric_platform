package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildHistoryRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BuildHistoryProjectionTest {
    private static final java.util.UUID ENVIRONMENT_ID =
            java.util.UUID.fromString("12345678-1234-4234-8234-000000000001");

    @Autowired BuildHistoryRepository history;
    @Autowired JdbcTemplate jdbc;
    private UUID id;

    @BeforeEach
    void reset() {
        jdbc.update("delete from build_history");
        jdbc.update("delete from deployment_history");
        jdbc.update("update history_cursor set cursor=null where consumer='build-history'");
        id = UUID.randomUUID();
    }

    @Test
    void offlineReplayRestoresEveryBuild() {
        var page = new ChangePage(List.of(event(1, 1, id), event(2, 1, UUID.randomUUID())), "2");
        history.apply(page, null);
        assertThat(
                        history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50))
                                .items())
                .hasSize(2);
        var initial =
                history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50)).items();
        jdbc.update("delete from build_history");
        jdbc.update("update history_cursor set cursor=null");
        history.apply(page, null);
        assertThat(
                        history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50))
                                .items())
                .isEqualTo(initial);
    }

    @Test
    void projectionAndCursorRollbackTogether() {
        var bad = new ChangeRecord(2, "repo", "UNSUPPORTED", id.toString(), 1, Map.of());
        assertThatThrownBy(
                        () ->
                                history.apply(
                                        new ChangePage(List.of(event(1, 1, id), bad), "2"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(history.cursor()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from build_history", Integer.class))
                .isZero();
    }

    @Test
    void duplicateAndOlderObjectVersionsDoNotRegress() {
        history.apply(new ChangePage(List.of(event(1, 3, id)), "1"), null);
        history.apply(new ChangePage(List.of(event(1, 3, id), event(2, 2, id)), "2"), "1");
        assertThat(
                        jdbc.queryForObject(
                                "select object_version from build_history where build_id=?",
                                Long.class,
                                id))
                .isEqualTo(3);
        assertThat(history.cursor()).isEqualTo("2");
        assertThatThrownBy(() -> history.apply(new ChangePage(List.of(event(3, 4, id)), "3"), "1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyPagePreservesCursor() {
        history.apply(new ChangePage(List.of(event(1, 1, id)), "1"), null);
        history.apply(new ChangePage(List.of(), "1"), "1");
        assertThat(history.cursor()).isEqualTo("1");
    }

    @Test
    void legacyQueryLinkUsesStableBuildMapping() {
        String project = "legacy-" + UUID.randomUUID();
        UUID release = UUID.randomUUID(), query = UUID.randomUUID();
        jdbc.update(
                "insert into"
                        + " semantic_project(project_id,repository_id,project_subdir,profile_binding_id)"
                        + " values(?,?,?,?)",
                project,
                "repo",
                ".",
                "binding");
        jdbc.update(
                "insert into"
                        + " semantic_release(release_id,project_id,release_sequence,target_commit_sha,build_source_sha,project_digest,config_version,idempotency_key,state)"
                        + " values(?,?,1,?,?,?,?,?,'FAILED')",
                release,
                project,
                "a".repeat(40),
                "a".repeat(40),
                "digest",
                "1",
                UUID.randomUUID());
        jdbc.update(
                "insert into"
                        + " semantic_query(query_id,project_id,release_id,idempotency_key,mode,status)"
                        + " values(?,?,?,?,'QUERY','FAILED')",
                query,
                project,
                release,
                UUID.randomUUID());
        try {
            assertThat(
                            jdbc.queryForObject(
                                    "select build_id from semantic_query where query_id=?",
                                    UUID.class,
                                    query))
                    .isNull();
            jdbc.queryForObject(
                    "select import_legacy_build_mapping(?,?,?,?)",
                    Boolean.class,
                    release,
                    id,
                    "repo",
                    "verified-report-sha256");
            jdbc.queryForObject(
                    "select import_legacy_build_mapping(?,?,?,?)",
                    Boolean.class,
                    release,
                    id,
                    "repo",
                    "verified-report-sha256");
            assertThat(
                            jdbc.queryForObject(
                                    "select build_id from semantic_query where query_id=?",
                                    UUID.class,
                                    query))
                    .isEqualTo(id);
            assertThat(
                            jdbc.queryForObject(
                                    "select release_id from semantic_query where query_id=?",
                                    UUID.class,
                                    query))
                    .isEqualTo(release);
            assertThatThrownBy(
                            () ->
                                    jdbc.queryForObject(
                                            "select import_legacy_build_mapping(?,?,?,?)",
                                            Boolean.class,
                                            release,
                                            UUID.randomUUID(),
                                            "other",
                                            "conflict"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.update("delete from legacy_build_mapping where release_id=?", release);
            jdbc.update("delete from semantic_project where project_id=?", project);
        }
    }

    @Test
    void deploymentFactsReplayWithoutOwningTheActivePointer() {
        var target =
                new ChangeRecord(
                        1,
                        "repo",
                        "DEPLOYMENT_TARGET",
                        "[repo,PREVIEW,feature/a]",
                        4,
                        Map.of("activeBuildId", id.toString(), "version", 3));
        var attempt =
                new ChangeRecord(
                        2,
                        "repo",
                        "DEPLOYMENT_ATTEMPT",
                        "[repo,PREVIEW,feature/a,1]",
                        2,
                        Map.of("buildId", id.toString(), "deploymentStatus", "DEPLOYED"));
        history.apply(new ChangePage(List.of(target, attempt), "2"), null);
        assertThat(jdbc.queryForObject("select count(*) from deployment_history", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select object_version from deployment_history where"
                                        + " object_type='DEPLOYMENT_TARGET'",
                                Long.class))
                .isEqualTo(4);
    }

    @Test
    void concurrentConsumersCannotSkipCommittedFacts() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var task =
                    (java.util.concurrent.Callable<Boolean>)
                            () -> {
                                start.await();
                                try {
                                    history.apply(
                                            new ChangePage(List.of(event(1, 1, id)), "1"), null);
                                    return true;
                                } catch (IllegalStateException e) {
                                    return false;
                                }
                            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("select count(*) from build_history", Integer.class))
                    .isEqualTo(1);
            assertThat(history.cursor()).isEqualTo("1");
        }
    }

    @Test
    void objectUpdatesBetweenPagesDoNotRepeatOrSkipBuilds() {
        var second = UUID.randomUUID();
        history.apply(new ChangePage(List.of(event(1, 1, id), event(2, 1, second)), "2"), null);
        var firstPage = history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 1));
        assertThat(firstPage.items()).extracting(BuildView::buildId).containsExactly(id);
        // 更新已经展示的构建和尚未展示的构建，游标排序位置都必须保持不变。
        history.apply(new ChangePage(List.of(event(3, 2, id), event(4, 2, second)), "4"), "2");
        var nextPage =
                history.list(
                        new BuildFilter(
                                ENVIRONMENT_ID, "repo", null, null, firstPage.nextCursor(), 10));
        assertThat(nextPage.items()).extracting(BuildView::buildId).containsExactly(second);
        assertThat(nextPage.nextCursor()).isNull();
    }

    @Test
    void historicalCursorIsBoundToRepositoryAndBranch() {
        history.apply(
                new ChangePage(List.of(event(1, 1, id), event(2, 1, UUID.randomUUID())), "2"),
                null);
        var first =
                history.list(new BuildFilter(ENVIRONMENT_ID, "repo", "feature/a", null, null, 1));
        assertThat(first.nextCursor()).isNotNull();
        assertThat(
                        history.list(
                                        new BuildFilter(
                                                ENVIRONMENT_ID,
                                                "repo",
                                                "feature/a",
                                                null,
                                                first.nextCursor(),
                                                1))
                                .items())
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                history.list(
                                        new BuildFilter(
                                                ENVIRONMENT_ID,
                                                "other",
                                                "feature/a",
                                                null,
                                                first.nextCursor(),
                                                1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedBuildRetainsServiceDiagnosticInHistory() {
        var summary = new HashMap<>(event(1, 1, id).summary());
        summary.put("buildStatus", "FAILED");
        summary.put("errorCode", "BUILD_FAILED");
        summary.put("phase", "COMPILE");
        history.apply(
                new ChangePage(
                        List.of(new ChangeRecord(1, "repo", "BUILD", id.toString(), 1, summary)),
                        "1"),
                null);
        var error =
                history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50))
                        .items()
                        .getFirst()
                        .error();
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("BUILD_FAILED");
        assertThat(error.buildId()).isEqualTo(id);
    }

    @Test
    void malformedSummaryDoesNotAdvanceProjection() {
        var summary = new HashMap<>(event(2, 2, id).summary());
        summary.put("buildId", UUID.randomUUID().toString());
        var wrong = new ChangeRecord(2, "repo", "BUILD", id.toString(), 2, summary);
        assertThatThrownBy(
                        () ->
                                history.apply(
                                        new ChangePage(List.of(event(1, 1, id), wrong), "2"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(history.cursor()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from build_history", Integer.class))
                .isZero();
    }

    private ChangeRecord event(long sequence, long version, UUID build) {
        var summary = new HashMap<String, Object>();
        summary.put("buildId", build.toString());
        summary.put("repository", "repo");
        summary.put("branchName", "feature/a");
        summary.put(
                "execution",
                new com.metricplatform.environment.EnvironmentContracts.ExecutionSnapshot(
                        ENVIRONMENT_ID,
                        1,
                        new com.metricplatform.environment.EnvironmentContracts.ConnectionTarget(
                                "starrocks", "warehouse", "localhost", 9030, null),
                        "warehouse-test",
                        "default_catalog",
                        "analytics",
                        2,
                        "Asia/Shanghai"));
        summary.put("buildStatus", "QUEUED");
        summary.put("createdAt", "2026-10-10T00:00:00Z");
        return new ChangeRecord(sequence, "repo", "BUILD", build.toString(), version, summary);
    }
}
