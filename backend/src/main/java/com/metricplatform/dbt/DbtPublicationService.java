package com.metricplatform.dbt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.dbt.DbtRuntimeDtos.CatalogSnapshot;
import com.metricplatform.dbt.DbtRuntimeDtos.RunSnapshot;
import com.metricplatform.gitsync.DbtProjectRevision;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** READY 候选的唯一生效事务：先导入完整目录，再切换 activeReleaseId。 */
@Service
@RequiredArgsConstructor
public class DbtPublicationService {
    private static final String RELEASE_PROJECT =
            "select project_id from semantic_release where release_id=?";
    private static final String LOCK_PROJECT =
            "select observed_sha,observed_project_digest,observed_config_version,active_release_id,publication_sequence,profile_binding_id from semantic_project where project_id=? for update";
    private static final String LOCK_RELEASE =
            "select project_id,release_sequence,expected_active_release_id,target_commit_sha,build_source_sha,project_digest,config_version,expected_toolchain_version,state from semantic_release where release_id=? for update";
    private static final String ACTIVE_SCHEMA =
            "select schema_name from semantic_release where release_id=?";
    private static final String SCHEMA_COLLISION =
            "select count(*) from semantic_release where schema_name=? and release_id<>? and state='PUBLISHED'";
    private static final String MARK_STALE =
            "update semantic_release set state='SUPERSEDED',updated_at=current_timestamp where release_id=? and state<>'PUBLISHED'";
    private static final String MARK_INVALID =
            "update semantic_release set state='FAILED',error_code=?,updated_at=current_timestamp where release_id=? and state<>'PUBLISHED'";
    private static final String SAVE_READY =
            "update semantic_release set runtime_run_id=?,schema_name=?,toolchain_version=?,validation_json=?::jsonb,updated_at=current_timestamp where release_id=?";
    private static final String SWITCH_ACTIVE =
            "update semantic_project set active_release_id=? where project_id=? and active_release_id is not distinct from ?";
    private static final String MARK_PUBLISHED =
            "update semantic_release set state='PUBLISHED',published_at=current_timestamp,updated_at=current_timestamp where release_id=? and state='READY'";
    private static final String READY = "READY", INVALID_RUN = "INVALID_RUN";
    private static final String SCHEMA_PATTERN = "[A-Za-z_][A-Za-z0-9_]{0,127}";
    private final JdbcTemplate jdbc;
    private final DbtCatalogStore catalogStore;
    private final ObjectMapper mapper;

    @Transactional
    public boolean promote(
            UUID releaseId,
            DbtProjectRevision verifiedHead,
            RunSnapshot run,
            CatalogSnapshot catalog) {
        String projectId = jdbc.queryForObject(RELEASE_PROJECT, String.class, releaseId);
        Map<String, Object> project = jdbc.queryForMap(LOCK_PROJECT, projectId);
        Map<String, Object> release = jdbc.queryForMap(LOCK_RELEASE, releaseId);

        // Git 已变、候选序号落后或活动指针变动都不能以旧结果覆盖新版本。
        boolean stale =
                !READY.equals(release.get("state"))
                        || !Objects.equals(project.get("observed_sha"), verifiedHead.sha())
                        || !Objects.equals(
                                project.get("observed_project_digest"),
                                verifiedHead.projectDigest())
                        || !Objects.equals(release.get("target_commit_sha"), verifiedHead.sha())
                        || !Objects.equals(
                                release.get("project_digest"), verifiedHead.projectDigest())
                        || !Objects.equals(
                                project.get("observed_config_version"),
                                release.get("config_version"))
                        || !Objects.equals(
                                ((Number) project.get("publication_sequence")).longValue(),
                                ((Number) release.get("release_sequence")).longValue())
                        || !Objects.equals(
                                project.get("active_release_id"),
                                release.get("expected_active_release_id"));
        if (stale) {
            jdbc.update(MARK_STALE, releaseId);
            return false;
        }
        String activeSchema =
                project.get("active_release_id") == null
                        ? null
                        : jdbc.queryForObject(
                                ACTIVE_SCHEMA, String.class, project.get("active_release_id"));
        if (!validRun(run, release, project, activeSchema)
                || catalog == null
                || catalog.resources() == null
                || catalog.resources().isEmpty()
                || catalog.dependencies() == null) {
            jdbc.update(MARK_INVALID, INVALID_RUN, releaseId);
            return false;
        }
        if (jdbc.queryForObject(SCHEMA_COLLISION, Integer.class, run.schemaName(), releaseId) > 0) {
            jdbc.update(MARK_INVALID, INVALID_RUN, releaseId);
            return false;
        }

        var resources =
                catalog.resources().stream()
                        .map(
                                value ->
                                        new DbtCatalogStore.Resource(
                                                value.resourceId(),
                                                value.nativeId(),
                                                value.kind(),
                                                value.name(),
                                                value.description(),
                                                value.sourcePath(),
                                                value.definition() == null
                                                        ? mapper.createObjectNode().toString()
                                                        : value.definition().toString(),
                                                value.queryable()))
                        .toList();
        var dependencies =
                catalog.dependencies().stream()
                        .map(
                                value ->
                                        new DbtCatalogStore.Dependency(
                                                value.upstreamResourceId(),
                                                value.downstreamResourceId(),
                                                value.kind()))
                        .toList();
        String validation =
                mapper.valueToTree(
                                Map.of(
                                        "manifestDigest", run.manifestDigest(),
                                        "semanticManifestDigest", run.semanticManifestDigest(),
                                        "runResultsDigest", run.runResultsDigest()))
                        .toString();
        jdbc.update(
                SAVE_READY,
                run.runId(),
                run.schemaName(),
                run.toolchainVersion(),
                validation,
                releaseId);
        catalogStore.replace(releaseId, resources, dependencies);
        if (jdbc.update(
                        SWITCH_ACTIVE,
                        releaseId,
                        projectId,
                        release.get("expected_active_release_id"))
                != 1) throw new IllegalStateException("发布指针竞争失败");
        if (jdbc.update(MARK_PUBLISHED, releaseId) != 1)
            throw new IllegalStateException("候选发布状态竞争失败");
        return true;
    }

    private static boolean validRun(
            RunSnapshot run,
            Map<String, Object> release,
            Map<String, Object> project,
            String activeSchema) {
        return run != null
                && run.runId() != null
                && READY.equals(run.state())
                && Objects.equals(run.commitSha(), release.get("build_source_sha"))
                && Objects.equals(run.projectDigest(), release.get("project_digest"))
                && Objects.equals(run.profileBindingId(), project.get("profile_binding_id"))
                && Objects.equals(run.configVersion(), release.get("config_version"))
                && run.toolchainVersion() != null
                && !run.toolchainVersion().isBlank()
                && (release.get("expected_toolchain_version") == null
                        || Objects.equals(
                                run.toolchainVersion(), release.get("expected_toolchain_version")))
                && run.schemaName() != null
                && run.schemaName().matches(SCHEMA_PATTERN)
                && !Objects.equals(run.schemaName(), activeSchema)
                && run.manifestDigest() != null
                && run.semanticManifestDigest() != null
                && run.runResultsDigest() != null
                && Boolean.TRUE.equals(run.allTestsPassed())
                && Boolean.TRUE.equals(run.representativeQueryPassed())
                && Boolean.TRUE.equals(run.queryCapability())
                && Boolean.TRUE.equals(run.relationsVerified());
    }
}
