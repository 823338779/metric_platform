package com.metricplatform.dbt;

import java.util.List;
import java.util.UUID;

/** dbt 查询仅接受类型化的 MetricFlow 参数，结果固定在提交时的 release。 */
public final class DbtQueryDtos {
    private DbtQueryDtos() {}

    public enum QueryMode {
        QUERY,
        EXPLAIN,
        PREVIEW,
        DIMENSION_VALUES
    }

    public record TypedQuery(
            UUID releaseId,
            List<String> metrics,
            List<String> groupBy,
            List<DbtRuntimeDtos.QueryFilter> filters,
            Integer limit,
            String startTime,
            String endTime,
            List<String> orderBy) {
        public TypedQuery(
                UUID releaseId, List<String> metrics, List<String> groupBy,
                List<DbtRuntimeDtos.QueryFilter> filters, Integer limit) {
            this(releaseId, metrics, groupBy, filters, limit, null, null, List.of());
        }
    }

    public record DimensionValuesRequest(
            UUID releaseId,
            List<String> metrics,
            String dimension,
            List<DbtRuntimeDtos.QueryFilter> filters,
            Integer limit) {}

    public record PreviewRequest(UUID releaseId, Integer limit) {}

    public record QuerySubmission(UUID queryId, UUID releaseId, String status) {}

    public record QueryStatus(
            UUID queryId,
            UUID releaseId,
            String targetCommitSha,
            String mode,
            String status,
            Object columns,
            Object rows,
            String errorCode,
            Boolean truncated,
            Long durationMs) {}
}
