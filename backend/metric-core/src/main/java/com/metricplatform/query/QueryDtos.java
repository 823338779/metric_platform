package com.metricplatform.query;

import com.metricplatform.engine.EngineDtos;
import java.util.List;
import java.util.UUID;

/** 语义查询仅接受类型化参数，结果固定在提交时的 release。 */
public final class QueryDtos {
    private QueryDtos() {}

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
            List<EngineDtos.QueryFilter> filters,
            Integer limit,
            String startTime,
            String endTime,
            List<String> orderBy) {
        public TypedQuery(
                UUID releaseId,
                List<String> metrics,
                List<String> groupBy,
                List<EngineDtos.QueryFilter> filters,
                Integer limit) {
            this(releaseId, metrics, groupBy, filters, limit, null, null, List.of());
        }
    }

    public record DimensionValuesRequest(
            UUID releaseId,
            List<String> metrics,
            String dimension,
            List<EngineDtos.QueryFilter> filters,
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
