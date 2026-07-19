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
            @NotNull Set<TimeGranularity> allowedGranularities,
            String description,
            String businessDefinition,
            List<String> aliases,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            String semanticType,
            String valueType,
            TimeGranularity defaultGranularity,
            String sensitivityLevel) {
        public CreateDimensionRequest(
                UUID datasetId,
                UUID datasetFieldId,
                String name,
                String displayName,
                DimensionType type,
                Set<TimeGranularity> allowedGranularities) {
            this(
                    datasetId,
                    datasetFieldId,
                    name,
                    displayName,
                    type,
                    allowedGranularities,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "ACTIVE",
                    "UNCERTIFIED",
                    null,
                    null,
                    null,
                    "INTERNAL");
        }
    }

    public record UpdateDimensionRequest(
            @NotNull UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull DimensionType type,
            @NotNull Set<TimeGranularity> allowedGranularities,
            @Min(0) long version,
            String description,
            String businessDefinition,
            List<String> aliases,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            String semanticType,
            String valueType,
            TimeGranularity defaultGranularity,
            String sensitivityLevel) {
        public UpdateDimensionRequest(
                UUID datasetFieldId,
                String name,
                String displayName,
                DimensionType type,
                Set<TimeGranularity> allowedGranularities,
                long version) {
            this(
                    datasetFieldId,
                    name,
                    displayName,
                    type,
                    allowedGranularities,
                    version,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    "UNCERTIFIED",
                    null,
                    null,
                    null,
                    "INTERNAL");
        }
    }

    public record CreateMetricRequest(
            @NotNull UUID datasetId,
            UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull Aggregation aggregation,
            String description,
            String businessDefinition,
            List<String> aliases,
            String unit,
            String formatPattern,
            String status,
            String domain,
            String owner,
            String certificationStatus,
            String aggregationBehavior,
            String additivity,
            String metricType,
            UUID defaultTimeDimensionId) {
        public CreateMetricRequest(
                UUID datasetId,
                UUID datasetFieldId,
                String name,
                String displayName,
                Aggregation aggregation) {
            this(
                    datasetId,
                    datasetFieldId,
                    name,
                    displayName,
                    aggregation,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "ACTIVE",
                    null,
                    null,
                    "UNCERTIFIED",
                    null,
                    null,
                    "BASE",
                    null);
        }
    }

    public record UpdateMetricRequest(
            UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotNull Aggregation aggregation,
            @Min(0) long version,
            String description,
            String businessDefinition,
            List<String> aliases,
            String unit,
            String formatPattern,
            String status,
            String domain,
            String owner,
            String certificationStatus,
            String aggregationBehavior,
            String additivity,
            String metricType,
            UUID defaultTimeDimensionId) {
        public UpdateMetricRequest(
                UUID datasetFieldId,
                String name,
                String displayName,
                Aggregation aggregation,
                long version) {
            this(
                    datasetFieldId,
                    name,
                    displayName,
                    aggregation,
                    version,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    null,
                    null,
                    "UNCERTIFIED",
                    null,
                    null,
                    "BASE",
                    null);
        }
    }

    public record DimensionResponse(
            UUID id,
            UUID datasetId,
            UUID datasetFieldId,
            String name,
            String displayName,
            DimensionType type,
            Set<TimeGranularity> allowedGranularities,
            long version,
            String description,
            String businessDefinition,
            List<String> aliases,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            String semanticType,
            String valueType,
            TimeGranularity defaultGranularity,
            String sensitivityLevel) {
        public DimensionResponse(
                UUID id,
                UUID datasetId,
                UUID datasetFieldId,
                String name,
                String displayName,
                DimensionType type,
                Set<TimeGranularity> allowedGranularities,
                long version) {
            this(
                    id,
                    datasetId,
                    datasetFieldId,
                    name,
                    displayName,
                    type,
                    allowedGranularities,
                    version,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "ACTIVE",
                    "UNCERTIFIED",
                    null,
                    null,
                    null,
                    "INTERNAL");
        }
    }

    public record MetricResponse(
            UUID id,
            UUID datasetId,
            UUID datasetFieldId,
            String name,
            String displayName,
            Aggregation aggregation,
            long version,
            String description,
            String businessDefinition,
            List<String> aliases,
            String unit,
            String formatPattern,
            String status,
            String domain,
            String owner,
            String certificationStatus,
            String aggregationBehavior,
            String additivity,
            String metricType,
            UUID defaultTimeDimensionId) {
        public MetricResponse(
                UUID id,
                UUID datasetId,
                UUID datasetFieldId,
                String name,
                String displayName,
                Aggregation aggregation,
                long version) {
            this(
                    id,
                    datasetId,
                    datasetFieldId,
                    name,
                    displayName,
                    aggregation,
                    version,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "ACTIVE",
                    null,
                    null,
                    "UNCERTIFIED",
                    null,
                    null,
                    "BASE",
                    null);
        }
    }

    public record PageResponse<T>(List<T> items, long total, int page, int size) {}

    public record PageRequest(
            String q, UUID datasetId, @Min(0) int page, @Min(1) @Max(200) int size) {}

    public record DependentResource(String type, UUID id, String name, String reason) {}

    public record DeletionImpactResponse(
            UUID resourceId,
            boolean deletable,
            List<DependentResource> dependencies,
            List<String> warnings) {
        public DeletionImpactResponse {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
