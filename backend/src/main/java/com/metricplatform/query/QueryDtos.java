package com.metricplatform.query;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class QueryDtos {
    private QueryDtos() {}

    public record DimensionSelection(@NotNull UUID dimensionId, TimeGranularity granularity) {}

    public record QueryRequest(
            @NotEmpty List<UUID> metricIds,
            List<@Valid DimensionSelection> dimensions,
            Set<UUID> relationshipIds) {
        public QueryRequest {
            metricIds = metricIds == null ? null : List.copyOf(metricIds);
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            relationshipIds = relationshipIds == null ? Set.of() : Set.copyOf(relationshipIds);
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

    public record ResultColumn(String name, String type) {}

    public enum SemanticObjectType {
        DATASET,
        METRIC,
        DIMENSION
    }

    public record SemanticSearchRequest(
            @NotNull String query, Set<UUID> datasetIds, Set<SemanticObjectType> objectTypes) {
        public SemanticSearchRequest {
            datasetIds = datasetIds == null ? Set.of() : Set.copyOf(datasetIds);
            objectTypes = objectTypes == null ? Set.of() : Set.copyOf(objectTypes);
        }
    }

    public record SemanticSearchResult(
            SemanticObjectType type, UUID id, UUID datasetId, String name, String displayName) {}

    public record SemanticSelection(UUID id, UUID datasetId, String name) {}

    public record AggregatePlanSummary(
            UUID datasetId,
            List<UUID> metricIds,
            List<UUID> dimensionIds,
            List<UUID> relationshipIds) {}

    public record ValidationResponse(
            boolean valid,
            List<SemanticSelection> metrics,
            List<SemanticSelection> dimensions,
            List<AggregatePlanSummary> aggregates,
            List<UUID> resolvedRelationshipIds,
            int parameterCount) {}

    public record QueryResponse(
            Object dataset,
            List<ResultColumn> columns,
            List<List<Object>> rows,
            List<Object> metrics,
            List<Object> dimensions) {}
}
