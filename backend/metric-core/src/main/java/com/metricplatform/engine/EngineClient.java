package com.metricplatform.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.engine.EngineDtos.*;
import java.util.UUID;

/** 外部运行服务只执行平台指定的固定版本，不持有平台发布指针。 */
public interface EngineClient {
    RunReceipt submitRun(RunRequest request);

    RunSnapshot getRun(UUID runId);

    RunSnapshot getRunByKey(UUID idempotencyKey);

    CatalogSnapshot getCatalog(UUID runId);

    JsonNode queryOptions(UUID runId, java.util.List<String> metrics);

    QueryReceipt submitQuery(QueryJobRequest request);

    QuerySnapshot getQuery(UUID queryId);

    QuerySnapshot getQueryByKey(UUID idempotencyKey);


    final class ServiceFailure extends RuntimeException {
        private final boolean transientFailure;
        private final int status;

        public ServiceFailure(boolean transientFailure, int status) {
            super("计算引擎调用失败");
            this.transientFailure = transientFailure;
            this.status = status;
        }

        public boolean transientFailure() {
            return transientFailure;
        }

        public int status() {
            return status;
        }
    }
}
