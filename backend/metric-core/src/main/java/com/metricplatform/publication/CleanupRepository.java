package com.metricplatform.publication;

import java.util.List;
import java.util.UUID;

/** 清理任务在独立事务中认领并更新物理运行版本。 */
public interface CleanupRepository {
    List<UUID> candidateRunIds(String projectId);

    boolean claim(String projectId, UUID runId);

    void markCleaned(String projectId, UUID runId);

    void markPending(String projectId, UUID runId);
}
