package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildHistoryRepository;
import com.metricplatform.catalog.BuildHistoryRepository.ChangePage;
import com.metricplatform.catalog.BuildHistoryRepository.ChangeRecord;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.DeploymentHistoryEntity;
import com.metricplatform.persistence.mapper.BuildHistoryMapper;
import com.metricplatform.persistence.mapper.DeploymentHistoryMapper;
import com.metricplatform.persistence.mapper.HistoryCursorMapper;
import com.metricplatform.persistence.mapper.SemanticProjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BuildHistoryProjectionTest {
    private static final java.util.UUID ENVIRONMENT_ID =
            java.util.UUID.fromString("12345678-1234-4234-8234-000000000001");

    @Autowired BuildHistoryRepository history;
    @Autowired TenantFixtureMapper tenants;
    @Autowired HistoryProjectionFixtureMapper fixtures;
    @Autowired BuildHistoryMapper builds;
    @Autowired DeploymentHistoryMapper deployments;
    @Autowired HistoryCursorMapper cursors;
    @Autowired SemanticProjectMapper projects;
    private UUID id;
    private TenantScope scope;
    private final List<UUID> ownedBuilds = new ArrayList<>();

    @BeforeEach
    void reset() {
        TenantFixture.manager(tenants);
        scope = TenantScope.open(TenantFixture.ID);
        clearProjection();
        id = ownedBuild();
    }

    private UUID ownedBuild() {
        UUID build = UUID.randomUUID();
        tenants.bindBuild(TenantFixture.ID, build, ENVIRONMENT_ID);
        ownedBuilds.add(build);
        return build;
    }

    private void clearProjection() {
        builds.delete(Wrappers.emptyWrapper());
        deployments.delete(Wrappers.emptyWrapper());
        cursors.delete(Wrappers.emptyWrapper());
    }

    @AfterEach
    void cleanup() {
        try {
            clearProjection();
            ownedBuilds.forEach(fixtures::deleteOwnership);
        } finally {
            scope.close();
        }
    }

    @Test
    void offlineReplayRestoresEveryBuild() {
        var page = new ChangePage(List.of(event(1, 1, id), event(2, 1, ownedBuild())), "2");
        history.apply(page, null);
        assertThat(
                        history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50))
                                .items())
                .hasSize(2);
        var initial =
                history.list(new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50)).items();
        clearProjection();
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
        assertThat(builds.selectCount(Wrappers.emptyWrapper())).isZero();
    }

    @Test
    void duplicateAndOlderObjectVersionsDoNotRegress() {
        history.apply(new ChangePage(List.of(event(1, 3, id)), "1"), null);
        history.apply(new ChangePage(List.of(event(1, 3, id), event(2, 2, id)), "2"), "1");
        assertThat(builds.selectById(id).getObjectVersion()).isEqualTo(3);
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
        fixtures.insertLegacyProject(project);
        fixtures.insertFailedRelease(release, project, "a".repeat(40), UUID.randomUUID());
        fixtures.insertFailedQuery(query, project, release, UUID.randomUUID());
        try {
            assertThat(fixtures.queryBuild(query)).isNull();
            fixtures.importLegacyMapping(
                    TenantScope.require(), release, id, "repo", "verified-report-sha256");
            fixtures.importLegacyMapping(
                    TenantScope.require(), release, id, "repo", "verified-report-sha256");
            assertThat(fixtures.queryBuild(query)).isEqualTo(id);
            assertThat(fixtures.queryRelease(query)).isEqualTo(release);
            assertThatThrownBy(
                            () ->
                                    fixtures.importLegacyMapping(
                                            TenantScope.require(),
                                            release,
                                            UUID.randomUUID(),
                                            "other",
                                            "conflict"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            fixtures.deleteLegacyMapping(release);
            projects.deleteById(project);
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
        assertThat(deployments.selectCount(Wrappers.emptyWrapper())).isEqualTo(2);
        var deployedTarget =
                deployments.selectOne(
                        Wrappers.<DeploymentHistoryEntity>lambdaQuery()
                                .eq(DeploymentHistoryEntity::getObjectType, "DEPLOYMENT_TARGET"));
        assertThat(deployedTarget.getObjectVersion()).isEqualTo(4);
    }

    @Test
    void concurrentConsumersCannotSkipCommittedFacts() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var task =
                    (java.util.concurrent.Callable<Boolean>)
                            () -> {
                                start.await();
                                try (var worker = TenantScope.open(TenantFixture.ID)) {
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
            assertThat(builds.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
            assertThat(history.cursor()).isEqualTo("1");
        }
    }

    @Test
    void targetCannotImportUnownedBuildInsideLatestAttempt() {
        var target =
                new ChangeRecord(
                        1,
                        "repo",
                        "DEPLOYMENT_TARGET",
                        "target",
                        1,
                        Map.of(
                                "activeBuildId",
                                id.toString(),
                                "latestAttempt",
                                Map.of("buildId", UUID.randomUUID().toString())));
        assertThatThrownBy(() -> history.apply(new ChangePage(List.of(target), "1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(history.cursor()).isNull();
        assertThat(deployments.selectCount(Wrappers.emptyWrapper())).isZero();
    }

    @Test
    void objectUpdatesBetweenPagesDoNotRepeatOrSkipBuilds() {
        var second = ownedBuild();
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
                new ChangePage(List.of(event(1, 1, id), event(2, 1, ownedBuild())), "2"), null);
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
        assertThat(builds.selectCount(Wrappers.emptyWrapper())).isZero();
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
