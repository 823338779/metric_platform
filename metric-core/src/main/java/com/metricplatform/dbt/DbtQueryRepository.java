package com.metricplatform.dbt;

import java.util.List;
import java.util.UUID;

/** 固定版本查询引用与任务恢复的持久化端口。 */
public interface DbtQueryRepository {
    record QueryRecord(
            UUID queryId, UUID releaseId, UUID idempotencyKey, UUID runtimeQueryId,
            String mode, String status, String errorCode) {}

    record PendingQuery(String projectId, UUID queryId) {}

    List<PendingQuery> pending(int limit);

    void reserve(UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey);

    void reserve(UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey, String mode);

    QueryRecord find(String projectId, UUID queryId);

    void attach(UUID queryId, UUID runtimeQueryId);

    void update(UUID queryId, UUID runtimeQueryId, String status, String errorCode);

    int countNonTerminal(UUID releaseId);

    void complete(UUID queryId);
}
