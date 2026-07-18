package com.metricplatform.query.planner;

import com.metricplatform.query.SemanticCatalogReader.DimensionDefinition;
import com.metricplatform.query.SemanticCatalogReader.MetricDefinition;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QueryPlan(
        List<DimensionProjection> dimensions,
        List<MetricProjection> metrics,
        List<DatasetAggregatePlan> aggregates,
        List<FilterProjection> filters,
        TimeProjection timeRange,
        List<QueryDtos.SortSelection> sort,
        int limit) {
    public QueryPlan {
        dimensions = List.copyOf(dimensions);
        metrics = List.copyOf(metrics);
        aggregates = List.copyOf(aggregates);
        filters = List.copyOf(filters);
        sort = List.copyOf(sort);
    }

    public QueryPlan(
            List<DimensionProjection> dimensions,
            List<MetricProjection> metrics,
            List<DatasetAggregatePlan> aggregates) {
        this(dimensions, metrics, aggregates, List.of(), null, List.of(), 1000);
    }

    public record DimensionProjection(
            DimensionDefinition definition, TimeGranularity granularity) {}

    public record MetricProjection(MetricDefinition definition) {}

    public record FilterProjection(
            DimensionDefinition definition,
            QueryDtos.FilterOperator operator,
            List<Object> values) {
        public FilterProjection {
            values = List.copyOf(values);
        }
    }

    public record TimeProjection(
            DimensionDefinition definition,
            Instant startInclusive,
            Instant endExclusive,
            String timezone,
            QueryDtos.TimePreset preset) {}

    public record ResolvedPath(UUID targetDatasetId, List<RelationshipDefinition> relationships) {
        public ResolvedPath {
            relationships = List.copyOf(relationships);
        }
    }

    public record DatasetAggregatePlan(
            UUID datasetId,
            List<ResolvedPath> paths,
            List<DimensionProjection> dimensions,
            List<MetricProjection> metrics) {
        public DatasetAggregatePlan {
            paths = List.copyOf(paths);
            dimensions = List.copyOf(dimensions);
            metrics = List.copyOf(metrics);
        }
    }
}
