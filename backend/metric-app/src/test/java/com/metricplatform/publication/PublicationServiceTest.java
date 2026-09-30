package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.engine.EngineDtos.*;
import com.metricplatform.persistence.publication.PublicationStore;
import com.metricplatform.persistence.store.CatalogStore;
import com.metricplatform.persistence.store.ReleaseStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublicationServiceTest {
    @Autowired ReleaseStore releases;
    @Autowired CatalogStore catalogStore;
    @Autowired PublicationStore publication;
    @Autowired JdbcTemplate jdbc;
    private String projectId;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PROFILE = "starrocks-profile";
    private static final String CONFIG = "config-1";

    @BeforeEach
    void setup() {
        projectId = "p" + UUID.randomUUID().toString().replace("-", "");
        releases.bindProject(projectId, "repo", "project", PROFILE);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from semantic_project where project_id=?", projectId);
    }

    @Test
    void readyCandidateAtomicallyBecomesActiveWithCatalog() {
        var head = revision('a', '1');
        UUID releaseId = releases.observe(projectId, head, CONFIG).releaseId();
        ready(releaseId);

        assertThat(publication.promote(releaseId, head, run('a', '1', "release_a"), catalog()))
                .isTrue();
        assertThat(releases.active(projectId)).isEqualTo(releaseId);
        assertThat(catalogStore.resources(releaseId))
                .extracting(CatalogStore.Resource::resourceId)
                .containsExactly("metric.sales.revenue");
    }

    @Test
    void emptyReleaseReplacesActiveCatalogAndAllowsOldPhysicalRunCleanup() {
        var first = revision('a', '1');
        UUID oldId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(oldId);
        var oldRun = run('a', '1', "release_a");
        assertThat(publication.promote(oldId, first, oldRun, catalog())).isTrue();
        var emptyHead = revision('b', '2');
        UUID emptyId = releases.observe(projectId, emptyHead, CONFIG).releaseId();
        ready(emptyId);
        var base = run('b', '2', "release_empty");
        var emptyRun =
                new RunSnapshot(
                        base.runId(),
                        base.state(),
                        base.commitSha(),
                        base.projectDigest(),
                        base.profileBindingId(),
                        base.configVersion(),
                        base.toolchainVersion(),
                        base.schemaName(),
                        base.manifestDigest(),
                        base.semanticManifestDigest(),
                        base.runResultsDigest(),
                        true,
                        false,
                        false,
                        true);

        assertThat(
                        publication.promote(
                                emptyId,
                                emptyHead,
                                emptyRun,
                                new CatalogSnapshot(List.of(), List.of())))
                .isTrue();
        assertThat(releases.active(projectId)).isEqualTo(emptyId);
        assertThat(catalogStore.resources(emptyId)).isEmpty();
        assertThat(catalogStore.resources(oldId)).hasSize(1);
        assertThat(cleanupStore.claim(projectId, oldRun.runId())).isTrue();
        assertThat(cleanupStore.claim(projectId, emptyRun.runId())).isFalse();
    }

    @Autowired com.metricplatform.persistence.store.CleanupStore cleanupStore;

    @Test
    void readmeOnlyTargetRequiresBuildAtTargetSha() {
        var source = revision('a', '1');
        UUID oldId = releases.observe(projectId, source, CONFIG).releaseId();
        var target = revision('b', '1');
        UUID releaseId = releases.observe(projectId, target, CONFIG).releaseId();
        ready(releaseId);

        assertThat(publication.promote(releaseId, target, run('b', '1', "release_b"), catalog()))
                .isTrue();
        assertThat(releases.active(projectId)).isEqualTo(releaseId);
        assertThat(
                        jdbc.queryForObject(
                                "select build_source_sha from semantic_release where release_id=?",
                                String.class,
                                releaseId))
                .isEqualTo(target.sha());
        assertThat(
                        jdbc.queryForObject(
                                "select state from semantic_release where release_id=?",
                                String.class,
                                oldId))
                .isEqualTo("SUPERSEDED");
    }

    @Test
    void newerObservedProjectContentSupersedesOldReadyCandidate() {
        var first = revision('a', '1');
        UUID oldId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(oldId);
        releases.observe(projectId, revision('b', '2'), CONFIG);

        assertThat(publication.promote(oldId, first, run('a', '1', "release_a"), catalog()))
                .isFalse();
        assertThat(releases.active(projectId)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select state from semantic_release where release_id=?",
                                String.class,
                                oldId))
                .isEqualTo("SUPERSEDED");
    }

    @Test
    void invalidQueryCapabilityKeepsPreviousRelease() {
        var first = revision('a', '1');
        UUID activeId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(activeId);
        assertThat(publication.promote(activeId, first, run('a', '1', "release_a"), catalog()))
                .isTrue();
        var second = revision('b', '2');
        UUID candidateId = releases.observe(projectId, second, CONFIG).releaseId();
        ready(candidateId);
        var bad = run('b', '2', "release_b");
        bad =
                new RunSnapshot(
                        bad.runId(),
                        bad.state(),
                        bad.commitSha(),
                        bad.projectDigest(),
                        bad.profileBindingId(),
                        bad.configVersion(),
                        bad.toolchainVersion(),
                        bad.schemaName(),
                        bad.manifestDigest(),
                        bad.semanticManifestDigest(),
                        bad.runResultsDigest(),
                        true,
                        true,
                        false,
                        true);

        assertThat(publication.promote(candidateId, second, bad, catalog())).isFalse();
        assertThat(releases.active(projectId)).isEqualTo(activeId);
    }

    @Test
    void catalogInsertFailureRollsBackPointerAndProjection() {
        var first = revision('a', '1');
        UUID activeId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(activeId);
        publication.promote(activeId, first, run('a', '1', "release_a"), catalog());
        var second = revision('b', '2');
        UUID candidateId = releases.observe(projectId, second, CONFIG).releaseId();
        ready(candidateId);
        var duplicate = catalog().resources().getFirst();
        var invalidCatalog = new CatalogSnapshot(List.of(duplicate, duplicate), List.of());

        assertThatThrownBy(
                        () ->
                                publication.promote(
                                        candidateId,
                                        second,
                                        run('b', '2', "release_b"),
                                        invalidCatalog))
                .isInstanceOf(RuntimeException.class);
        assertThat(releases.active(projectId)).isEqualTo(activeId);
        assertThat(catalogStore.resources(candidateId)).isEmpty();
    }

    @Test
    void candidateCannotPublishIfExpectedActivePointerChanged() {
        var first = revision('a', '1');
        UUID activeId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(activeId);
        publication.promote(activeId, first, run('a', '1', "release_a"), catalog());
        var second = revision('b', '2');
        UUID candidateId = releases.observe(projectId, second, CONFIG).releaseId();
        ready(candidateId);
        jdbc.update(
                "update semantic_project set active_release_id=null where project_id=?", projectId);

        assertThat(publication.promote(candidateId, second, run('b', '2', "release_b"), catalog()))
                .isFalse();
    }

    @Test
    void candidateCannotReuseActiveSchema() {
        var first = revision('a', '1');
        UUID activeId = releases.observe(projectId, first, CONFIG).releaseId();
        ready(activeId);
        publication.promote(activeId, first, run('a', '1', "release_a"), catalog());
        var second = revision('b', '2');
        UUID candidateId = releases.observe(projectId, second, CONFIG).releaseId();
        ready(candidateId);

        assertThat(publication.promote(candidateId, second, run('b', '2', "release_a"), catalog()))
                .isFalse();
        assertThat(releases.active(projectId)).isEqualTo(activeId);
    }

    @Test
    void missingPhysicalRelationsPreventsFirstPublication() {
        var head = revision('a', '1');
        UUID releaseId = releases.observe(projectId, head, CONFIG).releaseId();
        ready(releaseId);
        var complete = run('a', '1', "release_a");
        var missing =
                new RunSnapshot(
                        complete.runId(),
                        complete.state(),
                        complete.commitSha(),
                        complete.projectDigest(),
                        complete.profileBindingId(),
                        complete.configVersion(),
                        complete.toolchainVersion(),
                        complete.schemaName(),
                        complete.manifestDigest(),
                        complete.semanticManifestDigest(),
                        complete.runResultsDigest(),
                        true,
                        true,
                        true,
                        false);

        assertThat(publication.promote(releaseId, head, missing, catalog())).isFalse();
        assertThat(releases.active(projectId)).isNull();
    }

    @Test
    void mismatchedToolchainVersionCannotPublish() {
        var head = revision('a', '1');
        UUID id = releases.observe(projectId, head, CONFIG, "dbt-2/mf-2").releaseId();
        ready(id);
        assertThat(publication.promote(id, head, run('a', '1', "release_a"), catalog())).isFalse();
        assertThat(releases.active(projectId)).isNull();
    }

    private void ready(UUID releaseId) {
        jdbc.update("update semantic_release set state='READY' where release_id=?", releaseId);
    }

    private CatalogSnapshot catalog() {
        return new CatalogSnapshot(
                List.of(
                        new CatalogResource(
                                "metric.sales.revenue",
                                "metric.sales.revenue",
                                "metric",
                                "revenue",
                                "Revenue",
                                "project/models/metrics.yml",
                                mapper.createObjectNode(),
                                true)),
                List.of());
    }

    private static RunSnapshot run(char sha, char digest, String schema) {
        return new RunSnapshot(
                UUID.randomUUID(),
                "READY",
                String.valueOf(sha).repeat(40),
                String.valueOf(digest).repeat(64),
                PROFILE,
                CONFIG,
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

    private static ProjectRevision revision(char sha, char digest) {
        return new ProjectRevision(
                String.valueOf(sha).repeat(40), String.valueOf(digest).repeat(64));
    }
}
