package com.metricplatform.publication;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.gitsync.GitSyncProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 在数据库认领完成后调用运行服务回收无引用的物理 schema。 */
@Service
@ConditionalOnProperty(
        prefix = "metric-platform.dbt-runtime",
        name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReleaseCleanupService {
    private final CleanupRepository cleanup;
    private final EngineClient runtime;
    private final GitSyncProperties properties;

    @Scheduled(fixedDelayString = "${metric-platform.dbt-cleanup.poll-ms:60000}")
    public void scheduledCleanup() {
        if (properties.isEnabled() && properties.getProjectId() != null)
            cleanupEligible(properties.getProjectId());
    }

    public int cleanupEligible(String projectId) {
        int completed = 0;
        for (UUID runId : cleanup.candidateRunIds(projectId)) {
            if (!cleanup.claim(projectId, runId)) continue;
            try {
                runtime.cleanup(runId);
                cleanup.markCleaned(projectId, runId);
                completed++;
            } catch (EngineClient.ServiceFailure failure) {
                cleanup.markPending(projectId, runId);
                log.warn(
                        "release schema cleanup failed for run {} with status {}",
                        runId,
                        failure.status());
            }
        }
        return completed;
    }
}
