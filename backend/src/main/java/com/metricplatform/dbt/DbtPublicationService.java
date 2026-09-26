package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.dbt.DbtRuntimeDtos.CatalogSnapshot;
import com.metricplatform.dbt.DbtRuntimeDtos.RunSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** READY 候选在项目与版本行锁下导入目录并原子切换指针。 */
@Service
@RequiredArgsConstructor
public class DbtPublicationService implements DbtPublicationGateway {
    private static final String READY = "READY", PUBLISHED = "PUBLISHED";
    private static final String SUPERSEDED = "SUPERSEDED", FAILED = "FAILED";
    private static final String INVALID_RUN = "INVALID_RUN";
    private static final String SCHEMA_PATTERN = "[A-Za-z_][A-Za-z0-9_]{0,127}";
    private final SemanticProjectMapper projects;
    private final SemanticReleaseMapper releases;
    private final DbtCatalogRepository catalogStore;
    private final ObjectMapper mapper;

    @Transactional
    public boolean promote(
            UUID releaseId,
            DbtProjectRevision verifiedHead,
            RunSnapshot run,
            CatalogSnapshot catalog) {
        SemanticReleaseEntity initial = releases.selectById(releaseId);
        if (initial == null) throw new IllegalArgumentException("发布版本不存在");
        SemanticProjectEntity project = projects.lockProject(initial.getProjectId());
        SemanticReleaseEntity release = releases.lockRelease(releaseId);
        if (project == null || release == null) throw new IllegalArgumentException("发布版本不存在");

        // 目标 SHA、输入摘要、候选序号和活动指针均须仍是最新观测状态。
        boolean stale =
                !READY.equals(release.getState())
                        || !Objects.equals(project.getObservedSha(), verifiedHead.sha())
                        || !Objects.equals(
                                project.getObservedProjectDigest(), verifiedHead.projectDigest())
                        || !Objects.equals(release.getTargetCommitSha(), verifiedHead.sha())
                        || !Objects.equals(release.getProjectDigest(), verifiedHead.projectDigest())
                        || !Objects.equals(
                                project.getObservedConfigVersion(), release.getConfigVersion())
                        || !Objects.equals(
                                project.getPublicationSequence(), release.getReleaseSequence())
                        || !Objects.equals(
                                project.getActiveReleaseId(), release.getExpectedActiveReleaseId());
        if (stale) {
            mark(releaseId, SUPERSEDED, null);
            return false;
        }
        String activeSchema =
                project.getActiveReleaseId() == null
                        ? null
                        : releases.selectById(project.getActiveReleaseId()).getSchemaName();
        if (!validRun(run, release, project, activeSchema)
                || catalog == null
                || catalog.resources() == null
                || catalog.resources().isEmpty()
                || catalog.dependencies() == null) {
            mark(releaseId, FAILED, INVALID_RUN);
            return false;
        }
        if (releases.selectCount(
                        new LambdaQueryWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getSchemaName, run.schemaName())
                                .ne(SemanticReleaseEntity::getReleaseId, releaseId)
                                .eq(SemanticReleaseEntity::getState, PUBLISHED))
                > 0) {
            mark(releaseId, FAILED, INVALID_RUN);
            return false;
        }

        // 原生目录完整写入同一事务，任何导入或 CAS 失败都回滚旧版可见状态。
        var resources =
                catalog.resources().stream()
                        .map(
                                value ->
                                        new DbtCatalogRepository.Resource(
                                                value.resourceId(),
                                                value.nativeId(),
                                                value.kind().toUpperCase(java.util.Locale.ROOT),
                                                value.name(),
                                                value.description(),
                                                value.sourcePath(),
                                                definitionWithCatalogMetadata(value).toString(),
                                                value.queryable()))
                        .toList();
        var dependencies =
                catalog.dependencies().stream()
                        .map(
                                value ->
                                        new DbtCatalogRepository.Dependency(
                                                value.upstreamResourceId(),
                                                value.downstreamResourceId(),
                                                value.kind()))
                        .toList();
        String validation =
                mapper.valueToTree(
                                Map.of(
                                        "manifestDigest", run.manifestDigest(),
                                        "semanticManifestDigest", run.semanticManifestDigest(),
                                        "runResultsDigest", run.runResultsDigest(),
                                        "artifactSchemas", catalog.artifactSchemas()))
                        .toString();
        release.setRuntimeRunId(run.runId());
        release.setSchemaName(run.schemaName());
        release.setToolchainVersion(run.toolchainVersion());
        release.setValidationJson(validation);
        release.setUpdatedAt(now());
        releases.updateById(release);
        catalogStore.replace(releaseId, resources, dependencies);
        LambdaUpdateWrapper<SemanticProjectEntity> switchPointer =
                new LambdaUpdateWrapper<SemanticProjectEntity>()
                        .eq(SemanticProjectEntity::getProjectId, project.getProjectId());
        if (release.getExpectedActiveReleaseId() == null)
            switchPointer.isNull(SemanticProjectEntity::getActiveReleaseId);
        else
            switchPointer.eq(
                    SemanticProjectEntity::getActiveReleaseId,
                    release.getExpectedActiveReleaseId());
        int switched =
                projects.update(
                        switchPointer.set(SemanticProjectEntity::getActiveReleaseId, releaseId));
        if (switched != 1) throw new IllegalStateException("发布指针竞争失败");
        int published =
                releases.update(
                        new LambdaUpdateWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                                .eq(SemanticReleaseEntity::getState, READY)
                                .set(SemanticReleaseEntity::getState, PUBLISHED)
                                .set(SemanticReleaseEntity::getPublishedAt, now())
                                .set(SemanticReleaseEntity::getUpdatedAt, now()));
        if (published != 1) throw new IllegalStateException("候选发布状态竞争失败");
        return true;
    }

    private void mark(UUID releaseId, String state, String errorCode) {
        LambdaUpdateWrapper<SemanticReleaseEntity> update =
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                        .ne(SemanticReleaseEntity::getState, PUBLISHED)
                        .set(SemanticReleaseEntity::getState, state)
                        .set(SemanticReleaseEntity::getUpdatedAt, now());
        if (errorCode != null) update.set(SemanticReleaseEntity::getErrorCode, errorCode);
        releases.update(update);
    }

    private ObjectNode definitionWithCatalogMetadata(DbtRuntimeDtos.CatalogResource resource) {
        ObjectNode definition =
                resource.definition() != null && resource.definition().isObject()
                        ? (ObjectNode) resource.definition().deepCopy()
                        : mapper.createObjectNode();
        if (resource.relation() != null || resource.columns() != null) {
            ObjectNode catalogMetadata = mapper.createObjectNode();
            if (resource.relation() != null) catalogMetadata.put("relation", resource.relation());
            if (resource.columns() != null) catalogMetadata.set("columns", resource.columns());
            definition.set("_platformCatalog", catalogMetadata);
        }
        return definition;
    }

    private static boolean validRun(
            RunSnapshot run,
            SemanticReleaseEntity release,
            SemanticProjectEntity project,
            String activeSchema) {
        return run != null
                && run.runId() != null
                && READY.equals(run.state())
                && Objects.equals(run.commitSha(), release.getBuildSourceSha())
                && Objects.equals(run.projectDigest(), release.getProjectDigest())
                && Objects.equals(run.profileBindingId(), project.getProfileBindingId())
                && Objects.equals(run.configVersion(), release.getConfigVersion())
                && run.toolchainVersion() != null
                && !run.toolchainVersion().isBlank()
                && (release.getExpectedToolchainVersion() == null
                        || Objects.equals(
                                run.toolchainVersion(), release.getExpectedToolchainVersion()))
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

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
