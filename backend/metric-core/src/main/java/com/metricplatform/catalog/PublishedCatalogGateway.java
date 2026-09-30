package com.metricplatform.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** 服务拥有已发布协议；平台只保留身份、HTTP 边界与固定版本。 */
public interface PublishedCatalogGateway {
    JsonNode projects();
    JsonNode publication(String projectId);
    JsonNode releases(String projectId);
    JsonNode release(String projectId, UUID releaseId);
    JsonNode catalog(String projectId, UUID releaseId, String q, String kind, int page, int size);
    JsonNode resource(String projectId, UUID releaseId, String resourceId, String view);
    JsonNode queryOptions(String projectId, JsonNode request);
    JsonNode submitQuery(String projectId, JsonNode request);
    JsonNode query(String projectId, UUID queryId);
    JsonNode compatibility(String projectId, String operation, JsonNode request);

    /** 仅携带受控错误码，禁止传播上游正文中的执行诊断或连接信息。 */
    final class Failure extends RuntimeException {
        private final int status;
        private final String code;

        public Failure(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
