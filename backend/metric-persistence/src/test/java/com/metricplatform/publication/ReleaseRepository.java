package com.metricplatform.publication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 发布工作流依赖的持久化端口，不包含 MyBatis 或数据库实体。 */
public interface ReleaseRepository {
    enum ObservationKind {
        SKIPPED,
        START,
        REUSE
    }

    record ObservationResult(ObservationKind kind, UUID releaseId, UUID reusableRunId) {}

    record ReleaseWork(
            UUID releaseId,
            String projectId,
            String targetSha,
            String buildSourceSha,
            String projectDigest,
            String configVersion,
            UUID idempotencyKey,
            boolean submissionAttempted,
            UUID runId,
            String state,
            Instant retryAfter) {}

    /** 已发布目录与查询使用的固定运行版本信息。 */
    record ReleaseDetails(UUID runId, String buildSourceSha, String validationJson) {}

    void bindProject(
            String projectId, String repositoryId, String projectSubdir, String profileBindingId);

    ObservationResult observe(String projectId, ProjectRevision revision, String configVersion);

    ObservationResult observe(
            String projectId,
            ProjectRevision revision,
            String configVersion,
            String expectedToolchainVersion);

    void markFailed(UUID releaseId, String errorCode);

    UUID active(String projectId);

    ReleaseWork work(UUID releaseId);

    ReleaseDetails details(UUID releaseId);

    List<UUID> pending(String projectId);

    void markSubmitting(UUID idempotencyKey);

    void attachRun(UUID idempotencyKey, UUID runId);

    void markReady(UUID releaseId);

    int recordTransientFailure(UUID releaseId);

    void clearTransientFailure(UUID releaseId);
}
