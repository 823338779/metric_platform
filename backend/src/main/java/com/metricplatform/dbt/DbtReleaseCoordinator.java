package com.metricplatform.dbt;

import com.metricplatform.dbt.DbtRuntimeDtos.RunReceipt;
import com.metricplatform.dbt.DbtRuntimeDtos.RunRequest;
import com.metricplatform.dbt.DbtRuntimeDtos.RunSnapshot;
import com.metricplatform.gitsync.GitRefreshTrigger;
import com.metricplatform.gitsync.GitSnapshotReader;
import com.metricplatform.gitsync.GitSyncProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/** 从固定 main 观察目标提交，并根据持久候选推进外部构建和原子发布。 */
@Slf4j
public final class DbtReleaseCoordinator implements GitRefreshTrigger {
    private static final String THREAD = "metric-dbt-release",
            PREPARING = "PREPARING",
            BUILDING = "BUILDING",
            VALIDATING = "VALIDATING",
            READY = "READY",
            FAILED = "FAILED",
            PUBLISHED = "PUBLISHED",
            SUPERSEDED = "SUPERSEDED",
            SERVICE_REJECTED = "SERVICE_REJECTED",
            TEMPORARY_FAILURE = "TEMPORARY_FAILURE",
            RUN_FAILED = "RUN_FAILED",
            UNKNOWN_RUN_STATE = "UNKNOWN_RUN_STATE";
    private static final int MAX_TRANSIENT_FAILURES = 3;
    private final GitSnapshotReader reader;
    private final DbtReleaseStore releases;
    private final DbtPublicationService publication;
    private final DbtRuntimeClient runtime;
    private final GitSyncProperties properties;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name(THREAD).factory());
    private final AtomicBoolean queued = new AtomicBoolean();

    public DbtReleaseCoordinator(
            GitSnapshotReader reader,
            DbtReleaseStore releases,
            DbtPublicationService publication,
            DbtRuntimeClient runtime,
            GitSyncProperties properties) {
        this.reader = reader;
        this.releases = releases;
        this.publication = publication;
        this.runtime = runtime;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(
                this::tick, 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void requestRefresh() {
        if (queued.compareAndSet(false, true)) {
            try {
                executor.execute(
                        () -> {
                            queued.set(false);
                            tick();
                        });
            } catch (RejectedExecutionException e) {
                queued.set(false);
                throw e;
            }
        }
    }

    public void refresh() {
        releases.bindProject(
                properties.getProjectId(),
                properties.getRepositoryId(),
                properties.getProjectSubdir(),
                properties.getProfileBindingId());
        var head = reader.readDbtRevision(properties.getProjectSubdir());
        var observation =
                releases.observe(
                        properties.getProjectId(),
                        head,
                        properties.getConfigVersion(),
                        properties.getToolchainVersion());
        var work = releases.work(observation.releaseId());
        if (!FAILED.equals(work.state())
                && !PUBLISHED.equals(work.state())
                && !SUPERSEDED.equals(work.state())) advance(work.releaseId());
    }

    public void resumePending() {
        for (UUID releaseId : releases.pending(properties.getProjectId())) advance(releaseId);
    }

    private void tick() {
        try {
            refresh();
            resumePending();
        } catch (Exception e) {
            // 后台线程不得因一次 Git/数据库故障停止补偿；不打印可能含凭据的异常文本。
            log.warn("dbt 发布协调失败，异常类型={}", e.getClass().getSimpleName());
        }
    }

    private void advance(UUID releaseId) {
        var work = releases.work(releaseId);
        if (FAILED.equals(work.state())
                || PUBLISHED.equals(work.state())
                || SUPERSEDED.equals(work.state())) return;
        if (work.retryAfter() != null && work.retryAfter().isAfter(Instant.now())) return;
        try {
            UUID runId = work.runId();
            if (runId == null) runId = findOrSubmit(work);
            RunSnapshot run = runtime.getRun(runId);
            if (!runId.equals(run.runId())) {
                releases.markFailed(releaseId, SERVICE_REJECTED);
                return;
            }
            if (READY.equals(run.state())) {
                releases.markReady(releaseId);
                var catalog = runtime.getCatalog(runId);
                var verifiedHead = reader.readDbtRevision(properties.getProjectSubdir());
                publication.promote(releaseId, verifiedHead, run, catalog);
                releases.clearTransientFailure(releaseId);
            } else if (FAILED.equals(run.state())) {
                releases.markFailed(releaseId, RUN_FAILED);
            } else if (!PREPARING.equals(run.state())
                    && !BUILDING.equals(run.state())
                    && !VALIDATING.equals(run.state())) {
                releases.markFailed(releaseId, UNKNOWN_RUN_STATE);
            } else {
                releases.clearTransientFailure(releaseId);
            }
        } catch (DbtRuntimeClient.ServiceFailure e) {
            if (e.transientFailure()) {
                if (releases.recordTransientFailure(releaseId) >= MAX_TRANSIENT_FAILURES)
                    releases.markFailed(releaseId, TEMPORARY_FAILURE);
            } else {
                releases.markFailed(releaseId, SERVICE_REJECTED);
            }
        }
    }

    private UUID findOrSubmit(DbtReleaseStore.ReleaseWork work) {
        if (work.submissionAttempted()) {
            try {
                UUID runId = runtime.getRunByKey(work.idempotencyKey()).runId();
                releases.attachRun(work.idempotencyKey(), runId);
                return runId;
            } catch (DbtRuntimeClient.ServiceFailure e) {
                if (e.status() != 404) throw e;
            }
        }
        releases.markSubmitting(work.idempotencyKey());
        RunRequest request =
                new RunRequest(
                        work.projectId(),
                        work.buildSourceSha(),
                        work.projectDigest(),
                        properties.getProfileBindingId(),
                        work.configVersion(),
                        work.idempotencyKey());
        UUID runId;
        try {
            RunReceipt receipt = runtime.submitRun(request);
            runId = receipt.runId();
        } catch (DbtRuntimeClient.ServiceFailure e) {
            if (!e.transientFailure()) throw e;
            runId = runtime.getRunByKey(work.idempotencyKey()).runId();
        }
        releases.attachRun(work.idempotencyKey(), runId);
        return runId;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
