package com.metricplatform.dbt;

import com.metricplatform.gitsync.DbtProjectRevision;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 持久化项目观测与候选；项目行锁串行化来自 Webhook、轮询和重启恢复的观察。 */
@Repository
@RequiredArgsConstructor
public class DbtReleaseStore {
    private static final String BIND_PROJECT =
            "insert into semantic_project(project_id, repository_id, project_subdir, profile_binding_id) values (?,?,?,?) on conflict (project_id) do nothing";
    private static final String PROJECT_BINDING =
            "select repository_id, project_subdir, profile_binding_id from semantic_project where project_id=?";
    private static final String LOCK_PROJECT =
            "select observed_sha, observed_project_digest, observed_config_version, active_release_id, publication_sequence from semantic_project where project_id=? for update";
    private static final String LATEST_RELEASE =
            "select release_id, target_commit_sha, build_source_sha, project_digest, config_version, expected_toolchain_version, idempotency_key, submission_attempted, runtime_run_id, state from semantic_release where project_id=? order by release_sequence desc limit 1";
    private static final String UPDATE_OBSERVATION =
            "update semantic_project set observed_sha=?, observed_project_digest=?, observed_config_version=?, last_observation_status=?, last_observed_at=current_timestamp where project_id=?";
    private static final String INSERT_RELEASE =
            "insert into semantic_release(release_id,project_id,release_sequence,expected_active_release_id,target_commit_sha,build_source_sha,project_digest,config_version,expected_toolchain_version,idempotency_key,submission_attempted,runtime_run_id,state) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String INCREMENT_SEQUENCE =
            "update semantic_project set publication_sequence=? where project_id=?";
    private static final String SUPERSEDE =
            "update semantic_release set state='SUPERSEDED',updated_at=current_timestamp where release_id=? and state in ('PREPARING','BUILDING','VALIDATING','READY')";
    private static final String FAIL =
            "update semantic_release set state='FAILED',error_code=?,updated_at=current_timestamp where release_id=? and state not in ('PUBLISHED','SUPERSEDED')";
    private static final String ACTIVE =
            "select active_release_id from semantic_project where project_id=?";
    private static final String SELECT_WORK =
            "select release_id,project_id,target_commit_sha,build_source_sha,project_digest,config_version,idempotency_key,submission_attempted,runtime_run_id,state,retry_after from semantic_release where release_id=?";
    private static final String PENDING_WORK =
            "select release_id from semantic_release where project_id=? and state in ('PREPARING','BUILDING','VALIDATING','READY') order by release_sequence desc";
    private static final String MARK_SUBMITTING =
            "update semantic_release set submission_attempted=true,updated_at=current_timestamp where idempotency_key=? and state in ('PREPARING','BUILDING')";
    private static final String ATTACH_RUN =
            "update semantic_release set runtime_run_id=?,state='BUILDING',updated_at=current_timestamp where idempotency_key=? and state in ('PREPARING','BUILDING')";
    private static final String MARK_READY =
            "update semantic_release set state='READY',updated_at=current_timestamp where release_id=? and state in ('PREPARING','BUILDING','VALIDATING','READY')";
    private static final String RECORD_TRANSIENT_FAILURE =
            "update semantic_release set transient_retry_count=transient_retry_count+1,updated_at=current_timestamp where release_id=? returning transient_retry_count";
    private static final String CLEAR_TRANSIENT_FAILURE =
            "update semantic_release set transient_retry_count=0,retry_after=null where release_id=?";
    private static final String SET_RETRY_AFTER =
            "update semantic_release set retry_after=? where release_id=?";
    private static final String SKIPPED = "SKIPPED_UNCHANGED",
            CANDIDATE = "CANDIDATE",
            PREPARING = "PREPARING",
            FAILED = "FAILED",
            PUBLISHED = "PUBLISHED",
            SUPERSEDED_STATE = "SUPERSEDED";

    public enum ObservationKind {
        SKIPPED,
        START,
        REUSE
    }

    public record ObservationResult(ObservationKind kind, UUID releaseId, UUID reusableRunId) {}

    public record ReleaseWork(
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

    @Transactional
    public void bindProject(
            String projectId, String repositoryId, String projectSubdir, String profileBindingId) {
        jdbc.update(BIND_PROJECT, projectId, repositoryId, projectSubdir, profileBindingId);
        Map<String, Object> stored = jdbc.queryForMap(PROJECT_BINDING, projectId);
        if (!Objects.equals(stored.get("repository_id"), repositoryId)
                || !Objects.equals(stored.get("project_subdir"), projectSubdir)
                || !Objects.equals(stored.get("profile_binding_id"), profileBindingId))
            throw new IllegalStateException("dbt 项目绑定不可更改");
    }

    @Transactional
    public ObservationResult observe(
            String projectId, DbtProjectRevision revision, String configVersion) {
        return observe(projectId, revision, configVersion, null);
    }

    @Transactional
    public ObservationResult observe(
            String projectId,
            DbtProjectRevision revision,
            String configVersion,
            String expectedToolchainVersion) {
        Map<String, Object> project = jdbc.queryForMap(LOCK_PROJECT, projectId);
        var latest =
                jdbc.query(
                        LATEST_RELEASE,
                        rs ->
                                rs.next()
                                        ? new Latest(
                                                rs.getObject("release_id", UUID.class),
                                                rs.getString("target_commit_sha"),
                                                rs.getString("build_source_sha"),
                                                rs.getString("project_digest"),
                                                rs.getString("config_version"),
                                                rs.getString("expected_toolchain_version"),
                                                rs.getObject("idempotency_key", UUID.class),
                                                rs.getBoolean("submission_attempted"),
                                                rs.getObject("runtime_run_id", UUID.class),
                                                rs.getString("state"))
                                        : null,
                        projectId);

        boolean sameInput =
                latest != null
                        && Objects.equals(latest.projectDigest(), revision.projectDigest())
                        && Objects.equals(latest.configVersion(), configVersion)
                        && Objects.equals(
                                latest.expectedToolchainVersion(), expectedToolchainVersion);
        if (sameInput
                && (Objects.equals(latest.targetSha(), revision.sha())
                        || FAILED.equals(latest.state())
                        || PUBLISHED.equals(latest.state())
                        || SUPERSEDED_STATE.equals(latest.state()))) {
            jdbc.update(
                    UPDATE_OBSERVATION,
                    revision.sha(),
                    revision.projectDigest(),
                    configVersion,
                    SKIPPED,
                    projectId);
            return new ObservationResult(
                    ObservationKind.SKIPPED, latest.releaseId(), latest.runId());
        }

        // 同内容新 SHA 复用仍在构建的物理结果，但必须用新的目标 SHA 创建发布记录。
        boolean reuse = sameInput && latest != null;
        if (latest != null) jdbc.update(SUPERSEDE, latest.releaseId());
        long sequence = ((Number) project.get("publication_sequence")).longValue() + 1;
        UUID releaseId = UUID.randomUUID();
        jdbc.update(INCREMENT_SEQUENCE, sequence, projectId);
        jdbc.update(
                INSERT_RELEASE,
                releaseId,
                projectId,
                sequence,
                project.get("active_release_id"),
                revision.sha(),
                reuse ? latest.buildSourceSha() : revision.sha(),
                revision.projectDigest(),
                configVersion,
                expectedToolchainVersion,
                reuse ? latest.idempotencyKey() : UUID.randomUUID(),
                reuse && latest.submissionAttempted(),
                reuse ? latest.runId() : null,
                PREPARING);
        jdbc.update(
                UPDATE_OBSERVATION,
                revision.sha(),
                revision.projectDigest(),
                configVersion,
                CANDIDATE,
                projectId);
        return new ObservationResult(
                reuse ? ObservationKind.REUSE : ObservationKind.START,
                releaseId,
                reuse ? latest.runId() : null);
    }

    @Transactional
    public void markFailed(UUID releaseId, String errorCode) {
        jdbc.update(FAIL, errorCode, releaseId);
    }

    public UUID active(String projectId) {
        return jdbc.queryForObject(ACTIVE, UUID.class, projectId);
    }

    public ReleaseWork work(UUID releaseId) {
        return jdbc.queryForObject(
                SELECT_WORK,
                (rs, row) ->
                        new ReleaseWork(
                                rs.getObject("release_id", UUID.class),
                                rs.getString("project_id"),
                                rs.getString("target_commit_sha"),
                                rs.getString("build_source_sha"),
                                rs.getString("project_digest"),
                                rs.getString("config_version"),
                                rs.getObject("idempotency_key", UUID.class),
                                rs.getBoolean("submission_attempted"),
                                rs.getObject("runtime_run_id", UUID.class),
                                rs.getString("state"),
                                rs.getTimestamp("retry_after") == null
                                        ? null
                                        : rs.getTimestamp("retry_after").toInstant()),
                releaseId);
    }

    public java.util.List<UUID> pending(String projectId) {
        return jdbc.query(PENDING_WORK, (rs, row) -> rs.getObject(1, UUID.class), projectId);
    }

    @Transactional
    public void markSubmitting(UUID idempotencyKey) {
        jdbc.update(MARK_SUBMITTING, idempotencyKey);
    }

    @Transactional
    public void attachRun(UUID idempotencyKey, UUID runId) {
        jdbc.update(ATTACH_RUN, runId, idempotencyKey);
    }

    @Transactional
    public void markReady(UUID releaseId) {
        jdbc.update(MARK_READY, releaseId);
    }

    @Transactional
    public int recordTransientFailure(UUID releaseId) {
        int count = jdbc.queryForObject(RECORD_TRANSIENT_FAILURE, Integer.class, releaseId);
        jdbc.update(
                SET_RETRY_AFTER,
                java.sql.Timestamp.from(Instant.now().plusSeconds(1L << Math.min(count - 1, 8))),
                releaseId);
        return count;
    }

    @Transactional
    public void clearTransientFailure(UUID releaseId) {
        jdbc.update(CLEAR_TRANSIENT_FAILURE, releaseId);
    }

    private record Latest(
            UUID releaseId,
            String targetSha,
            String buildSourceSha,
            String projectDigest,
            String configVersion,
            String expectedToolchainVersion,
            UUID idempotencyKey,
            boolean submissionAttempted,
            UUID runId,
            String state) {}

    private final JdbcTemplate jdbc;
}
