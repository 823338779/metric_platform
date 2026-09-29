package com.metricplatform.publication;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos.RunReceipt;
import com.metricplatform.engine.EngineDtos.RunRequest;
import com.metricplatform.engine.EngineDtos.RunSnapshot;
import java.time.Instant;
import java.util.UUID;

/** 固定 main 观察、全量构建、过时判定与 READY 发布的业务流程。 */
public final class ReleaseWorkflow {
    private static final String PREPARING = "PREPARING", BUILDING = "BUILDING";
    private static final String VALIDATING = "VALIDATING", READY = "READY";
    private static final String FAILED = "FAILED", PUBLISHED = "PUBLISHED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String SERVICE_REJECTED = "SERVICE_REJECTED";
    private static final String TEMPORARY_FAILURE = "TEMPORARY_FAILURE";
    private static final String RUN_FAILED = "RUN_FAILED", UNKNOWN_RUN_STATE = "UNKNOWN_RUN_STATE";
    private static final int MAX_TRANSIENT_FAILURES = 3;
    private final GitHeadGateway reader;
    private final ReleaseRepository releases;
    private final PublicationGateway publication;
    private final EngineClient runtime;
    private final Config config;

    public record Config(
            String projectId,
            String repositoryId,
            String projectSubdir,
            String profileBindingId,
            String configVersion,
            String toolchainVersion,
            String schemaName) {}

    public ReleaseWorkflow(
            GitHeadGateway reader,
            ReleaseRepository releases,
            PublicationGateway publication,
            EngineClient runtime,
            Config config) {
        this.reader = reader;
        this.releases = releases;
        this.publication = publication;
        this.runtime = runtime;
        this.config = config;
    }

    public void refresh() {
        releases.bindProject(
                config.projectId(),
                config.repositoryId(),
                config.projectSubdir(),
                config.profileBindingId());
        ProjectRevision head = reader.readRevision(config.projectSubdir());
        var observation =
                releases.observe(
                        config.projectId(),
                        head,
                        config.configVersion(),
                        config.toolchainVersion());
        var work = releases.work(observation.releaseId());
        if (!FAILED.equals(work.state())
                && !PUBLISHED.equals(work.state())
                && !SUPERSEDED.equals(work.state())) advance(work.releaseId());
    }

    public void resumePending() {
        for (UUID releaseId : releases.pending(config.projectId())) advance(releaseId);
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
                var verifiedHead = reader.readRevision(config.projectSubdir());
                publication.promote(releaseId, verifiedHead, run, catalog, config.schemaName());
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
        } catch (EngineClient.ServiceFailure failure) {
            if (failure.transientFailure()) {
                if (releases.recordTransientFailure(releaseId) >= MAX_TRANSIENT_FAILURES)
                    releases.markFailed(releaseId, TEMPORARY_FAILURE);
            } else {
                releases.markFailed(releaseId, SERVICE_REJECTED);
            }
        }
    }

    private UUID findOrSubmit(ReleaseRepository.ReleaseWork work) {
        if (work.submissionAttempted()) {
            try {
                UUID runId = runtime.getRunByKey(work.idempotencyKey()).runId();
                releases.attachRun(work.idempotencyKey(), runId);
                return runId;
            } catch (EngineClient.ServiceFailure failure) {
                if (failure.status() != 404) throw failure;
            }
        }
        releases.markSubmitting(work.idempotencyKey());
        RunRequest request =
                new RunRequest(
                        work.projectId(),
                        work.buildSourceSha(),
                        work.projectDigest(),
                        config.profileBindingId(),
                        work.configVersion(),
                        work.idempotencyKey());
        UUID runId;
        try {
            RunReceipt receipt = runtime.submitRun(request);
            runId = receipt.runId();
        } catch (EngineClient.ServiceFailure failure) {
            if (!failure.transientFailure()) throw failure;
            runId = runtime.getRunByKey(work.idempotencyKey()).runId();
        }
        releases.attachRun(work.idempotencyKey(), runId);
        return runId;
    }
}
