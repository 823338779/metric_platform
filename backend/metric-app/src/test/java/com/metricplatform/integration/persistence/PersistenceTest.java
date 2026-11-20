package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import com.metricplatform.persistence.service.SemanticProjectService;
import com.metricplatform.persistence.service.SemanticReleaseService;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceTest {
    @Autowired TenantFixtureMapper tenants;
    @Autowired PersistenceFixtureMapper schema;
    @Autowired SemanticProjectService projects;
    @Autowired SemanticReleaseService releaseRows;
    @Autowired ReleaseStore releases;
    @Autowired QueryStore queries;
    @Autowired CatalogStore catalog;
    private String projectId;
    private TenantScope scope;

    @BeforeEach
    void setup() {
        TenantFixture.manager(tenants);
        scope = TenantScope.open(TenantFixture.ID);
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", "starrocks-profile");
    }

    @AfterEach
    void cleanup() {
        try {
            projects.removeById(projectId);
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
            assertThat(schema.countTables(table)).isEqualTo(1);
        }
        assertThat(schema.cleanupStateComment()).isNotBlank();
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
        assertThat(projects.getById(projectId).getObservedSha()).isEqualTo("b".repeat(40));
        assertThat(
                        releaseRows
                                .lambdaQuery()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .count())
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
                        releaseRows
                                .lambdaQuery()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .count())
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
                        releaseRows
                                .lambdaQuery()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void nonTerminalQueryReferencesRelease() {
        UUID releaseId = releases.observe(projectId, revision('a', '1'), "config-1").releaseId();
        releaseRows
                .lambdaUpdate()
                .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                .set(SemanticReleaseEntity::getState, "PUBLISHED")
                .update();
        projects.lambdaUpdate()
                .eq(SemanticProjectEntity::getProjectId, projectId)
                .set(SemanticProjectEntity::getActiveReleaseId, releaseId)
                .update();
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
