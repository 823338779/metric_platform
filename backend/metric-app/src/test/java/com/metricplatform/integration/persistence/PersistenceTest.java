package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.store.CatalogStore;
import com.metricplatform.persistence.store.QueryStore;
import com.metricplatform.persistence.store.ReleaseStore;
import com.metricplatform.publication.ProjectRevision;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceTest {
    private static final String ACTIVATE_RELEASE_SQL =
            "update semantic_project set active_release_id=? where project_id=?";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReleaseStore releases;
    @Autowired QueryStore queries;
    @Autowired CatalogStore catalog;
    private String projectId;
    private TenantScope scope;

    @BeforeEach
    void setup() {
        TenantFixture.manager(jdbc);
        scope = TenantScope.open(TenantFixture.ID);
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", "starrocks-profile");
    }

    @AfterEach
    void cleanup() {
        try {
            jdbc.update(
                    "delete from semantic_project where tenant_id=? and project_id=?",
                    TenantFixture.ID,
                    projectId);
        } finally {
            scope.close();
        }
    }

    @Test
    void migrationCreatesFiveVersionTablesWithColumnComments() {
        for (String table :
                new String[] {
                    "semantic_project",
                    "semantic_release",
                    "semantic_resource",
                    "semantic_dependency",
                    "semantic_query"
                }) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from information_schema.tables where"
                                            + " table_name = ?",
                                    Integer.class,
                                    table))
                    .isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select col_description('semantic_release'::regclass, attnum) from"
                                        + " pg_attribute where attrelid='semantic_release'::regclass"
                                        + " and attname='cleanup_state'",
                                String.class))
                .isNotBlank();
    }

    @Test
    void readmeOnlyCommitStartsIndependentBuildAndSameShaIsIdempotent() {
        var first = releases.observe(projectId, revision('a', '1'), "config-1");
        var second = releases.observe(projectId, revision('b', '1'), "config-1");
        assertThat(first.kind()).isEqualTo(ReleaseStore.ObservationKind.START);
        assertThat(second.kind()).isEqualTo(ReleaseStore.ObservationKind.START);
        assertThat(releases.work(second.releaseId()).idempotencyKey())
                .isNotEqualTo(releases.work(first.releaseId()).idempotencyKey());
        assertThat(releases.work(second.releaseId()).buildSourceSha()).isEqualTo("b".repeat(40));
        assertThat(releases.work(second.releaseId()).runId()).isNull();
        assertThat(second.releaseId()).isNotEqualTo(first.releaseId());
        assertThat(releases.observe(projectId, revision('b', '1'), "config-1").releaseId())
                .isEqualTo(second.releaseId());
        assertThat(
                        jdbc.queryForObject(
                                "select observed_sha from semantic_project where project_id=?",
                                String.class,
                                projectId))
                .isEqualTo("b".repeat(40));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(2);
    }

    @Test
    void failedShaIsNotRetriedButNewShaWithSameDigestStartsBuild() {
        var first = releases.observe(projectId, revision('a', '1'), "config-1");
        releases.markFailed(first.releaseId(), "INVALID_DEFINITION");
        assertThat(releases.observe(projectId, revision('a', '1'), "config-1").kind())
                .isEqualTo(ReleaseStore.ObservationKind.SKIPPED);
        var next = releases.observe(projectId, revision('b', '1'), "config-1");
        assertThat(next.kind()).isEqualTo(ReleaseStore.ObservationKind.START);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(2);
        assertThat(releases.observe(projectId, revision('c', '2'), "config-1").kind())
                .isEqualTo(ReleaseStore.ObservationKind.START);
    }

    @Test
    void concurrentObservationHasOneCandidate() throws Exception {
        var latch = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<ReleaseStore.ObservationResult> task =
                    () -> {
                        latch.await();
                        try (var worker = TenantScope.open(TenantFixture.ID)) {
                            return releases.observe(projectId, revision('a', '1'), "config-1");
                        }
                    };
            var one = executor.submit(task);
            var two = executor.submit(task);
            latch.countDown();
            assertThat(one.get().releaseId()).isEqualTo(two.get().releaseId());
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
    }

    @Test
    void nonTerminalQueryReferencesRelease() {
        UUID releaseId = releases.observe(projectId, revision('a', '1'), "config-1").releaseId();
        jdbc.update("update semantic_release set state='PUBLISHED' where release_id=?", releaseId);
        jdbc.update(ACTIVATE_RELEASE_SQL, releaseId, projectId);
        UUID queryId = UUID.randomUUID();
        queries.reserve(queryId, projectId, releaseId, UUID.randomUUID());
        assertThat(queries.countNonTerminal(releaseId)).isEqualTo(1);
        queries.complete(queryId);
        assertThat(queries.countNonTerminal(releaseId)).isZero();
    }

    @Test
    void catalogIsStoredPerReleaseWithoutChangingResourceIdentity() {
        UUID releaseId = releases.observe(projectId, revision('a', '1'), "config-1").releaseId();
        catalog.replace(
                releaseId,
                List.of(
                        new CatalogStore.Resource(
                                "model.sales.orders",
                                "model.sales.orders",
                                "model",
                                "orders",
                                "Orders",
                                "project/models/orders.sql",
                                "{}",
                                true),
                        new CatalogStore.Resource(
                                "metric.sales.revenue",
                                "metric.sales.revenue",
                                "metric",
                                "revenue",
                                "Revenue",
                                "project/models/metrics.yml",
                                "{}",
                                true)),
                List.of(
                        new CatalogStore.Dependency(
                                "model.sales.orders", "metric.sales.revenue", "semantic")));
        assertThat(catalog.resources(releaseId))
                .extracting(CatalogStore.Resource::resourceId)
                .containsExactly("metric.sales.revenue", "model.sales.orders");
        assertThat(catalog.dependencies(releaseId))
                .containsExactly(
                        new CatalogStore.Dependency(
                                "model.sales.orders", "metric.sales.revenue", "semantic"));
    }

    @Test
    void catalogJsonbAndCompositeReleaseKeyRoundTrip() {
        UUID first = releases.observe(projectId, revision('a', '1'), "config-1").releaseId();
        UUID second = releases.observe(projectId, revision('b', '2'), "config-1").releaseId();
        String nativeDefinition = "{\"meta\":{\"label\":\"营业额\",\"precision\":18}}";
        var resource =
                new CatalogStore.Resource(
                        "metric.sales.revenue",
                        "metric.sales.revenue",
                        "METRIC",
                        "revenue",
                        "营业额",
                        "models/orders.yml",
                        nativeDefinition,
                        true);
        catalog.replace(first, List.of(resource), List.of());
        catalog.replace(second, List.of(resource), List.of());
        assertThat(catalog.resources(first).getFirst().definitionJson())
                .contains("营业额", "precision");
        catalog.replace(second, List.of(), List.of());
        assertThat(catalog.resources(first)).hasSize(1);
        assertThat(catalog.resources(second)).isEmpty();
    }

    private static ProjectRevision revision(char sha, char digest) {
        return new ProjectRevision(
                String.valueOf(sha).repeat(40), String.valueOf(digest).repeat(64));
    }
}
