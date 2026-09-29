package com.metricplatform.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import com.metricplatform.persistence.service.SemanticProjectService;
import com.metricplatform.persistence.service.SemanticReleaseService;
import com.metricplatform.publication.ProjectRevision;
import com.metricplatform.publication.ReleaseRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 项目行锁串行化 main 观察，其他发布状态用 MyBatis Plus 条件更新。 */
@Repository
@RequiredArgsConstructor
public class ReleaseStore implements ReleaseRepository {
    private static final String SKIPPED = "SKIPPED_UNCHANGED", CANDIDATE = "CANDIDATE";
    private static final String PREPARING = "PREPARING",
            BUILDING = "BUILDING",
            VALIDATING = "VALIDATING";
    private static final String READY = "READY", FAILED = "FAILED", PUBLISHED = "PUBLISHED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private final SemanticProjectService projects;
    private final SemanticReleaseService releases;

    @Transactional
    public void bindProject(
            String projectId, String repositoryId, String projectSubdir, String profileBindingId) {
        SemanticProjectEntity candidate = new SemanticProjectEntity();
        candidate.setProjectId(projectId);
        candidate.setRepositoryId(repositoryId);
        candidate.setProjectSubdir(projectSubdir);
        candidate.setProfileBindingId(profileBindingId);
        projects.insertBinding(candidate);
        SemanticProjectEntity stored = projects.getById(projectId);
        if (stored == null
                || !Objects.equals(stored.getRepositoryId(), repositoryId)
                || !Objects.equals(stored.getProjectSubdir(), projectSubdir)
                || !Objects.equals(stored.getProfileBindingId(), profileBindingId))
            throw new IllegalStateException("语义项目绑定不可更改");
    }

    @Transactional
    public ObservationResult observe(
            String projectId, ProjectRevision revision, String configVersion) {
        return observe(projectId, revision, configVersion, null);
    }

    @Transactional
    public ObservationResult observe(
            String projectId,
            ProjectRevision revision,
            String configVersion,
            String expectedToolchainVersion) {
        SemanticProjectEntity project = projects.lockProject(projectId);
        if (project == null) throw new NoSuchElementException("语义项目不存在");
        List<SemanticReleaseEntity> latestPage =
                releases.page(
                                new Page<>(1, 1, false),
                                new LambdaQueryWrapper<SemanticReleaseEntity>()
                                        .eq(SemanticReleaseEntity::getProjectId, projectId)
                                        .orderByDesc(SemanticReleaseEntity::getReleaseSequence))
                        .getRecords();
        SemanticReleaseEntity latest = latestPage.isEmpty() ? null : latestPage.getFirst();
        boolean sameInput =
                latest != null
                        && Objects.equals(latest.getProjectDigest(), revision.projectDigest())
                        && Objects.equals(latest.getConfigVersion(), configVersion)
                        && Objects.equals(
                                latest.getExpectedToolchainVersion(), expectedToolchainVersion);
        if (sameInput
                && (Objects.equals(latest.getTargetCommitSha(), revision.sha())
                        || List.of(FAILED, PUBLISHED, SUPERSEDED).contains(latest.getState()))) {
            observeProject(project, revision, configVersion, SKIPPED);
            return new ObservationResult(
                    ObservationKind.SKIPPED, latest.getReleaseId(), latest.getRuntimeRunId());
        }

        // 相同输入的新 SHA 复用仍在构建的 run，新候选仍有自己的目标 SHA 与序号。
        boolean reuse = sameInput;
        if (latest != null)
            releases.update(
                    new LambdaUpdateWrapper<SemanticReleaseEntity>()
                            .eq(SemanticReleaseEntity::getReleaseId, latest.getReleaseId())
                            .in(
                                    SemanticReleaseEntity::getState,
                                    PREPARING,
                                    BUILDING,
                                    VALIDATING,
                                    READY)
                            .set(SemanticReleaseEntity::getState, SUPERSEDED)
                            .set(SemanticReleaseEntity::getUpdatedAt, now()));
        long sequence = project.getPublicationSequence() + 1;
        UUID releaseId = UUID.randomUUID();
        SemanticReleaseEntity candidate = new SemanticReleaseEntity();
        candidate.setReleaseId(releaseId);
        candidate.setProjectId(projectId);
        candidate.setReleaseSequence(sequence);
        candidate.setExpectedActiveReleaseId(project.getActiveReleaseId());
        candidate.setTargetCommitSha(revision.sha());
        candidate.setBuildSourceSha(reuse ? latest.getBuildSourceSha() : revision.sha());
        candidate.setProjectDigest(revision.projectDigest());
        candidate.setConfigVersion(configVersion);
        candidate.setExpectedToolchainVersion(expectedToolchainVersion);
        candidate.setIdempotencyKey(reuse ? latest.getIdempotencyKey() : UUID.randomUUID());
        candidate.setSubmissionAttempted(
                reuse && Boolean.TRUE.equals(latest.getSubmissionAttempted()));
        candidate.setRuntimeRunId(reuse ? latest.getRuntimeRunId() : null);
        candidate.setState(PREPARING);
        releases.save(candidate);
        project.setPublicationSequence(sequence);
        observeProject(project, revision, configVersion, CANDIDATE);
        return new ObservationResult(
                reuse ? ObservationKind.REUSE : ObservationKind.START,
                releaseId,
                candidate.getRuntimeRunId());
    }

    private void observeProject(
            SemanticProjectEntity project,
            ProjectRevision revision,
            String configVersion,
            String status) {
        project.setObservedSha(revision.sha());
        project.setObservedProjectDigest(revision.projectDigest());
        project.setObservedConfigVersion(configVersion);
        project.setLastObservationStatus(status);
        project.setLastObservedAt(now());
        projects.updateById(project);
    }

    @Transactional
    public void markFailed(UUID releaseId, String errorCode) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                        .notIn(SemanticReleaseEntity::getState, PUBLISHED, SUPERSEDED)
                        .set(SemanticReleaseEntity::getState, FAILED)
                        .set(SemanticReleaseEntity::getErrorCode, errorCode)
                        .set(SemanticReleaseEntity::getUpdatedAt, now()));
    }

    public UUID active(String projectId) {
        SemanticProjectEntity project = projects.getById(projectId);
        if (project == null) throw new NoSuchElementException("语义项目不存在");
        return project.getActiveReleaseId();
    }

    public ReleaseWork work(UUID releaseId) {
        SemanticReleaseEntity row = releases.getById(releaseId);
        if (row == null) throw new NoSuchElementException("发布版本不存在");
        return new ReleaseWork(
                row.getReleaseId(),
                row.getProjectId(),
                row.getTargetCommitSha(),
                row.getBuildSourceSha(),
                row.getProjectDigest(),
                row.getConfigVersion(),
                row.getIdempotencyKey(),
                Boolean.TRUE.equals(row.getSubmissionAttempted()),
                row.getRuntimeRunId(),
                row.getState(),
                row.getRetryAfter() == null ? null : row.getRetryAfter().toInstant());
    }

    public ReleaseDetails details(UUID releaseId) {
        SemanticReleaseEntity row = releases.getById(releaseId);
        if (row == null) throw new NoSuchElementException("发布版本不存在");
        return new ReleaseDetails(
                row.getRuntimeRunId(), row.getBuildSourceSha(), row.getValidationJson());
    }

    public List<UUID> pending(String projectId) {
        return releases
                .list(
                        new LambdaQueryWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .in(
                                        SemanticReleaseEntity::getState,
                                        PREPARING,
                                        BUILDING,
                                        VALIDATING,
                                        READY)
                                .orderByDesc(SemanticReleaseEntity::getReleaseSequence))
                .stream()
                .map(SemanticReleaseEntity::getReleaseId)
                .toList();
    }

    @Transactional
    public void markSubmitting(UUID idempotencyKey) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getIdempotencyKey, idempotencyKey)
                        .in(SemanticReleaseEntity::getState, PREPARING, BUILDING)
                        .set(SemanticReleaseEntity::getSubmissionAttempted, true)
                        .set(SemanticReleaseEntity::getUpdatedAt, now()));
    }

    @Transactional
    public void attachRun(UUID idempotencyKey, UUID runId) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getIdempotencyKey, idempotencyKey)
                        .in(SemanticReleaseEntity::getState, PREPARING, BUILDING)
                        .set(SemanticReleaseEntity::getRuntimeRunId, runId)
                        .set(SemanticReleaseEntity::getState, BUILDING)
                        .set(SemanticReleaseEntity::getUpdatedAt, now()));
    }

    @Transactional
    public void markReady(UUID releaseId) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                        .in(SemanticReleaseEntity::getState, PREPARING, BUILDING, VALIDATING, READY)
                        .set(SemanticReleaseEntity::getState, READY)
                        .set(SemanticReleaseEntity::getUpdatedAt, now()));
    }

    @Transactional
    public int recordTransientFailure(UUID releaseId) {
        SemanticReleaseEntity row = releases.lockRelease(releaseId);
        if (row == null) throw new NoSuchElementException("发布版本不存在");
        int count = row.getTransientRetryCount() + 1;
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                        .set(SemanticReleaseEntity::getTransientRetryCount, count)
                        .set(
                                SemanticReleaseEntity::getRetryAfter,
                                now().plusSeconds(1L << Math.min(count - 1, 8)))
                        .set(SemanticReleaseEntity::getUpdatedAt, now()));
        return count;
    }

    @Transactional
    public void clearTransientFailure(UUID releaseId) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getReleaseId, releaseId)
                        .set(SemanticReleaseEntity::getTransientRetryCount, 0)
                        .set(SemanticReleaseEntity::getRetryAfter, null));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
