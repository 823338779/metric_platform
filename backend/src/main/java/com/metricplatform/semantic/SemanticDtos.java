package com.metricplatform.semantic;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SemanticDtos {
    private SemanticDtos() {}

    public enum DimensionType {
        STANDARD,
        TIME
    }

    public enum TimeGranularity {
        DAY,
        WEEK,
        MONTH,
        QUARTER,
        YEAR
    }

    public enum Aggregation {
        COUNT,
        COUNT_DISTINCT,
        SUM,
        AVG,
        MIN,
        MAX
    }

    public record CreateDimensionRequest(
            @NotNull UUID datasetId,
            @NotNull UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull DimensionType type,
            @NotNull Set<TimeGranularity> allowedGranularities) {}

    public record UpdateDimensionRequest(
            @NotNull UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull DimensionType type,
            @NotNull Set<TimeGranularity> allowedGranularities,
            @Min(0) long version) {}

    public record CreateMetricRequest(
            @NotNull UUID datasetId,
            UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull Aggregation aggregation) {}

    public record UpdateMetricRequest(
            UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull Aggregation aggregation,
            @Min(0) long version) {}

    public record DimensionResponse(
            UUID id,
            UUID datasetId,
            UUID datasetFieldId,
            String name,
            String displayName,
            DimensionType type,
            Set<TimeGranularity> allowedGranularities,
            long version) {}

    public record MetricResponse(
            UUID id,
            UUID datasetId,
            UUID datasetFieldId,
            String name,
            String displayName,
            Aggregation aggregation,
            long version) {}

    public record PageResponse<T>(List<T> items, long total, int page, int size) {}

    public record PageRequest(
            String q, UUID datasetId, @Min(0) int page, @Min(1) @Max(200) int size) {}
}
