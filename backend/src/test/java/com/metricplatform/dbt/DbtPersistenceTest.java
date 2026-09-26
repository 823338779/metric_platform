package com.metricplatform.dbt;

import static org.assertj.core.api.Assertions.*;

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
class DbtPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DbtReleaseStore releases;
    @Autowired DbtQueryStore queries;
    @Autowired DbtCatalogStore catalog;
    private String projectId;

    @BeforeEach
    void setup() {
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", "starrocks-profile");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from semantic_project where project_id = ?", projectId);
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
                                    "select count(*) from information_schema.tables where table_name = ?",
                                    Integer.class,
                                    table))
                    .isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select col_description('semantic_release'::regclass, attnum) from pg_attribute where attrelid='semantic_release'::regclass and attname='cleanup_state'",
                                String.class))
                .isNotBlank();
    }

    @Test
    void readmeOnlyCommitUpdatesObservationWithoutNewRelease() {
        var first = releases.observe(projectId, revision('a', '1'), "config-1");
        var second = releases.observe(projectId, revision('b', '1'), "config-1");
        assertThat(first.kind()).isEqualTo(DbtReleaseStore.ObservationKind.START);
        assertThat(second.kind()).isEqualTo(DbtReleaseStore.ObservationKind.REUSE);
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
    void deterministicFailureDoesNotRetrySameDigest() {
        var first = releases.observe(projectId, revision('a', '1'), "config-1");
        releases.markFailed(first.releaseId(), "INVALID_DEFINITION");
        var next = releases.observe(projectId, revision('b', '1'), "config-1");
        assertThat(next.kind()).isEqualTo(DbtReleaseStore.ObservationKind.SKIPPED);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from semantic_release where project_id=?",
                                Integer.class,
                                projectId))
                .isEqualTo(1);
        assertThat(releases.observe(projectId, revision('c', '2'), "config-1").kind())
                .isEqualTo(DbtReleaseStore.ObservationKind.START);
    }

    @Test
    void concurrentObservationHasOneCandidate() throws Exception {
        var latch = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one =
                    executor.submit(
                            () -> {
                                latch.await();
                                return releases.observe(projectId, revision('a', '1'), "config-1");
                            });
            var two =
                    executor.submit(
                            () -> {
                                latch.await();
                                return releases.observe(projectId, revision('a', '1'), "config-1");
                            });
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
                        new DbtCatalogStore.Resource(
                                "model.sales.orders",
                                "model.sales.orders",
                                "model",
                                "orders",
                                "Orders",
                                "project/models/orders.sql",
                                "{}",
                                true),
                        new DbtCatalogStore.Resource(
                                "metric.sales.revenue",
                                "metric.sales.revenue",
                                "metric",
                                "revenue",
                                "Revenue",
                                "project/models/metrics.yml",
                                "{}",
                                true)),
                List.of(
                        new DbtCatalogStore.Dependency(
                                "model.sales.orders", "metric.sales.revenue", "semantic")));
        assertThat(catalog.resources(releaseId))
                .extracting(DbtCatalogStore.Resource::resourceId)
                .containsExactly("metric.sales.revenue", "model.sales.orders");
        assertThat(catalog.dependencies(releaseId))
                .containsExactly(
                        new DbtCatalogStore.Dependency(
                                "model.sales.orders", "metric.sales.revenue", "semantic"));
    }

    @Test
    void catalogJsonbAndCompositeReleaseKeyRoundTrip() {
        UUID first = releases.observe(projectId, revision('a', '1'), "config-1").releaseId();
        UUID second = releases.observe(projectId, revision('b', '2'), "config-1").releaseId();
        String nativeDefinition = "{\"meta\":{\"label\":\"营业额\",\"precision\":18}}";
        var resource =
                new DbtCatalogStore.Resource(
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

    private static DbtProjectRevision revision(char sha, char digest) {
        return new DbtProjectRevision(
                String.valueOf(sha).repeat(40), String.valueOf(digest).repeat(64));
    }
}
