package com.metricplatform.query;

import java.util.UUID;

/** 保留平台身份与服务查询的归因关联。 */
public interface QueryAuditRepository {
    void record(UUID queryId, UUID buildId, UUID userId, String idempotencyKey);
}
