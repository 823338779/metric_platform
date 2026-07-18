package com.metricplatform.query.planner;

import com.metricplatform.query.SemanticCatalogReader.DimensionDefinition;
import com.metricplatform.query.SemanticCatalogReader.MetricDefinition;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import java.util.List;
import java.util.UUID;

public record QueryPlan(
        List<DimensionProjection> dimensions,
        List<MetricProjection> metrics,
        List<DatasetAggregatePlan> aggregates) {
    public QueryPlan {
        dimensions = List.copyOf(dimensions);
        metrics = List.copyOf(metrics);
        aggregates = List.copyOf(aggregates);
    }

    public record DimensionProjection(
            DimensionDefinition definition, TimeGranularity granularity) {}

    public record MetricProjection(MetricDefinition definition) {}

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
