package com.metricplatform.query;

import java.util.UUID;

/** 保留平台身份与同步执行的本地审计关联。 */
public interface QueryAuditRepository {
    void record(UUID executionId, UUID buildId, UUID userId);
}
