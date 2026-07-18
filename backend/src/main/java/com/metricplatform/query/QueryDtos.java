package com.metricplatform.query;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class QueryDtos {
    private QueryDtos() {}

    public record DimensionSelection(@NotNull UUID dimensionId, TimeGranularity granularity) {}

    public enum FilterOperator {
        EQ, NE, GT, GTE, LT, LTE, BETWEEN, IN, CONTAINS, IS_NULL, IS_NOT_NULL
    }

    public record DimensionFilter(
            @NotNull UUID dimensionId,
            @NotNull FilterOperator operator,
            List<Object> values) {
        public DimensionFilter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public enum TimePreset {
        TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, MONTH_TO_DATE, QUARTER_TO_DATE,
        YEAR_TO_DATE, LAST_MONTH, LAST_QUARTER, LAST_YEAR
    }

    public record TimeRange(
            @NotNull UUID dimensionId,
            Instant start,
            Instant end,
            TimePreset preset,
            @NotBlank String timezone) {}

    public enum SortDirection { ASC, DESC }

    public enum SortTargetType { METRIC, DIMENSION }

    public record SortSelection(
            @NotNull SortTargetType type,
            @NotNull UUID objectId,
            @NotNull SortDirection direction) {}

    public record QueryRequest(
            @NotEmpty List<UUID> metricIds,
            List<@Valid DimensionSelection> dimensions,
            Set<UUID> relationshipIds,
            List<@Valid DimensionFilter> filters,
            @Valid TimeRange timeRange,
            List<@Valid SortSelection> sort,
            @Min(1) @Max(1000) Integer limit) {
        public QueryRequest {
            metricIds = metricIds == null ? null : List.copyOf(metricIds);
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            relationshipIds = relationshipIds == null ? Set.of() : Set.copyOf(relationshipIds);
            filters = filters == null ? List.of() : List.copyOf(filters);
            sort = sort == null ? List.of() : List.copyOf(sort);
            limit = limit == null ? 1000 : Math.min(1000, limit);
        }

        public QueryRequest(
                List<UUID> metricIds,
                List<DimensionSelection> dimensions,
                Set<UUID> relationshipIds) {
            this(metricIds, dimensions, relationshipIds, List.of(), null, List.of(), 1000);
        }

        @Deprecated
        public QueryRequest(UUID ignoredDatasetId, List<UUID> metricIds, List<UUID> dimensionIds) {
            this(
                    metricIds,
                    dimensionIds == null
                            ? List.of()
                            : dimensionIds.stream()
                                    .map(id -> new DimensionSelection(id, null))
                                    .toList(),
                    Set.of());
        }

        @JsonIgnore
        @Deprecated
        public UUID datasetId() {
            return null;
        }

        @JsonIgnore
        @Deprecated
        public List<UUID> dimensionIds() {
            return dimensions.stream().map(DimensionSelection::dimensionId).toList();
        }
    }

    public record ResultColumn(
            String name,
            String type,
            SemanticObjectType semanticType,
            UUID semanticObjectId,
            String displayName,
            String logicalType,
            String unit,
            String formatPattern,
            TimeGranularity granularity) {
        public ResultColumn(String name, String type) {
            this(name, type, null, null, name, type, null, null, null);
        }
    }

    public enum SemanticObjectType { DATASET, METRIC, DIMENSION }

    public record SemanticSearchRequest(
            @NotNull String query,
            Set<UUID> datasetIds,
            Set<SemanticObjectType> objectTypes,
            Set<String> domains,
            Set<String> statuses,
            boolean certifiedOnly,
            @Min(1) @Max(100) Integer topK) {
        public SemanticSearchRequest {
            datasetIds = datasetIds == null ? Set.of() : Set.copyOf(datasetIds);
            objectTypes = objectTypes == null ? Set.of() : Set.copyOf(objectTypes);
            domains = domains == null ? Set.of() : Set.copyOf(domains);
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            topK = topK == null ? 20 : Math.min(100, topK);
        }

        public SemanticSearchRequest(
                String query, Set<UUID> datasetIds, Set<SemanticObjectType> objectTypes) {
            this(query, datasetIds, objectTypes, Set.of(), Set.of(), false, 20);
        }
    }

    public record SemanticSearchResult(
            SemanticObjectType type,
            UUID id,
            UUID datasetId,
            String name,
            String displayName,
            String description,
            List<String> aliases,
            double score,
            List<String> matchReasons,
            String status,
            String certificationStatus,
            String domain,
            long version,
            Instant updatedAt,
            Map<String, Object> summary) {
        public SemanticSearchResult(
                SemanticObjectType type, UUID id, UUID datasetId, String name, String displayName) {
            this(type, id, datasetId, name, displayName, null, List.of(), 0, List.of(), null, null,
                    null, 0, null, Map.of());
        }
    }

    public record SemanticObjectReference(@NotNull SemanticObjectType type, @NotNull UUID id) {}

    public record SemanticResolveRequest(
            @NotEmpty List<@Valid SemanticObjectReference> objects,
            Set<UUID> relationshipIds) {
        public SemanticResolveRequest {
            objects = objects == null ? null : List.copyOf(objects);
            relationshipIds = relationshipIds == null ? Set.of() : Set.copyOf(relationshipIds);
        }
    }

    public record RelationshipPathSummary(
            UUID relationshipId,
            String name,
            String displayPath,
            UUID sourceDatasetId,
            UUID targetDatasetId,
            String cardinality,
            String semanticRole,
            boolean fanoutSafe) {}

    public record ResolvedSemanticObject(
            SemanticObjectType type,
            UUID id,
            UUID datasetId,
            String name,
            String displayName,
            String description,
            String businessDefinition,
            List<String> aliases,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            String datasetGrain,
            String unit,
            String formatPattern,
            String valueType,
            Set<TimeGranularity> allowedGranularities,
            String freshnessPolicy,
            String qualityStatus,
            long version) {}

    public record SemanticResolveResponse(
            String catalogVersion,
            List<ResolvedSemanticObject> objects,
            List<RelationshipPathSummary> relationshipPaths) {}

    public record CapabilitiesResponse(
            String apiVersion,
            String catalogVersion,
            Set<String> queryFeatures,
            Map<String, Integer> limits,
            Set<TimePreset> timePresets,
            boolean planTokenSupported) {}

    public enum ConnectivityPathStatus { UNIQUE_PATH, AMBIGUOUS_PATH }

    public record DatasetConnectivity(UUID datasetId, UUID dataSourceId) {}

    public record ConnectivityPath(
            UUID sourceDatasetId,
            UUID targetDatasetId,
            ConnectivityPathStatus status,
            List<UUID> relationshipIds) {
        public ConnectivityPath {
            relationshipIds = relationshipIds == null ? List.of() : List.copyOf(relationshipIds);
        }
    }

    public record QueryConnectivityResponse(
            String catalogVersion,
            List<DatasetConnectivity> datasets,
            List<ConnectivityPath> paths) {}

    public enum MemberMatchMode { AUTO, EXACT, PREFIX, CONTAINS }

    public record DimensionMemberSearchRequest(
            @NotBlank String query,
            MemberMatchMode mode,
            @Min(1) @Max(100) Integer topK) {
        public DimensionMemberSearchRequest {
            mode = mode == null ? MemberMatchMode.AUTO : mode;
            topK = topK == null ? 20 : Math.min(100, topK);
        }
    }

    public record DimensionMember(String value, String displayValue) {}

    public record DimensionMemberSearchResponse(
            UUID dimensionId, List<DimensionMember> members, boolean truncated) {}

    public record SemanticSelection(UUID id, UUID datasetId, String name) {}

    public record AggregatePlanSummary(
            UUID datasetId,
            List<UUID> metricIds,
            List<UUID> dimensionIds,
            List<UUID> relationshipIds) {}

    public record ValidationIssue(
            String code,
            String message,
            SemanticObjectType objectType,
            UUID objectId,
            List<RelationshipPathSummary> candidatePaths) {}

    public record ValidationResponse(
            boolean valid,
            List<SemanticSelection> metrics,
            List<SemanticSelection> dimensions,
            List<AggregatePlanSummary> aggregates,
            List<UUID> resolvedRelationshipIds,
            int parameterCount,
            List<ValidationIssue> issues,
            List<String> warnings,
            QueryRequest normalizedRequest,
            String catalogVersion,
            String planToken) {
        public ValidationResponse(
                boolean valid,
                List<SemanticSelection> metrics,
                List<SemanticSelection> dimensions,
                List<AggregatePlanSummary> aggregates,
                List<UUID> resolvedRelationshipIds,
                int parameterCount) {
            this(valid, metrics, dimensions, aggregates, resolvedRelationshipIds, parameterCount,
                    List.of(), List.of(), null, null, null);
        }
    }

    public record QueryEvidence(
            UUID queryId,
            String catalogVersion,
            Map<UUID, Long> definitionVersions,
            List<UUID> relationshipIds) {}

    public record QueryResponse(
            Object dataset,
            List<ResultColumn> columns,
            List<List<Object>> rows,
            List<Object> metrics,
            List<Object> dimensions,
            UUID queryId,
            int rowCount,
            boolean truncated,
            List<DimensionFilter> appliedFilters,
            TimeRange appliedTimeRange,
            List<String> freshnessWarnings,
            String catalogVersion,
            QueryEvidence evidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sql,
            long durationMs) {
        public QueryResponse(
                Object dataset,
                List<ResultColumn> columns,
                List<List<Object>> rows,
                List<Object> metrics,
                List<Object> dimensions,
                String sql,
                long durationMs) {
            this(dataset, columns, rows, metrics, dimensions, null, rows == null ? 0 : rows.size(),
                    false, List.of(), null, List.of(), null, null, sql, durationMs);
        }

        public QueryResponse withoutSql() {
            return new QueryResponse(
                    dataset, columns, rows, metrics, dimensions, queryId, rowCount, truncated,
                    appliedFilters, appliedTimeRange, freshnessWarnings, catalogVersion, evidence,
                    null, durationMs);
        }
    }
}
