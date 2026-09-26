package com.metricplatform.dbt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** 与外部 dbt/MetricFlow 服务交换的结构化、无凭据 DTO。 */
public final class DbtRuntimeDtos {
    private DbtRuntimeDtos() {}

    public record RunRequest(
            String projectId,
            String commitSha,
            String projectDigest,
            String profileBindingId,
            String configVersion,
            UUID idempotencyKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunReceipt(UUID runId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunSnapshot(
            UUID runId,
            String state,
            String commitSha,
            String projectDigest,
            String profileBindingId,
            String configVersion,
            String toolchainVersion,
            String schemaName,
            String manifestDigest,
            String semanticManifestDigest,
            String runResultsDigest,
            Boolean allTestsPassed,
            Boolean representativeQueryPassed,
            Boolean queryCapability,
            Boolean relationsVerified) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogSnapshot(
            List<CatalogResource> resources, List<CatalogDependency> dependencies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogResource(
            String resourceId,
            String nativeId,
            String kind,
            String name,
            String description,
            String sourcePath,
            JsonNode definition,
            boolean queryable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogDependency(
            String upstreamResourceId, String downstreamResourceId, String kind) {}

    public record QueryFilter(String field, String operator, JsonNode value) {}

    public record QueryJobRequest(
            UUID runId,
            UUID idempotencyKey,
            String mode,
            List<String> metrics,
            List<String> groupBy,
            List<QueryFilter> filters,
            Integer limit,
            String datasetResourceId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryReceipt(UUID queryId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuerySnapshot(
            UUID queryId,
            String state,
            JsonNode columns,
            JsonNode rows,
            String errorCode,
            Boolean truncated,
            Long durationMs) {
        public QuerySnapshot(
                UUID queryId, String state, JsonNode columns, JsonNode rows, String errorCode) {
            this(queryId, state, columns, rows, errorCode, null, null);
        }
    }
}
