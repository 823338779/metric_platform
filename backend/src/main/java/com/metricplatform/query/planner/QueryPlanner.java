package com.metricplatform.query.planner;

import com.metricplatform.query.QueryDtos;
import com.metricplatform.query.SemanticCatalogReader;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.semantic.SemanticDtos.DimensionType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class QueryPlanner {
    private final SemanticCatalogReader catalog;

    public QueryPlanner(SemanticCatalogReader catalog) {
        this.catalog = catalog;
    }

    public QueryPlan plan(QueryDtos.QueryRequest request) {
        if (request.metricIds() == null || request.metricIds().isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        List<QueryPlan.MetricProjection> metrics =
                request.metricIds().stream()
                        .map(catalog::metric)
                        .map(QueryPlan.MetricProjection::new)
                        .toList();
        List<QueryPlan.DimensionProjection> dimensions =
                request.dimensions().stream()
                        .map(
                                selection ->
                                        dimension(
                                                selection,
                                                catalog.dimension(selection.dimensionId())))
                        .toList();

        UUID dataSourceId = metrics.getFirst().definition().dataset().getDataSourceId();
        if (metrics.stream()
                .anyMatch(
                        metric ->
                                !dataSourceId.equals(
                                        metric.definition().dataset().getDataSourceId()))) {
            throw new IllegalArgumentException(
                    "all metrics in one query must use the same data source");
        }

        List<RelationshipDefinition> relationships =
                catalog.relationships().stream()
                        .filter(
                                relationship ->
                                        request.relationshipIds().isEmpty()
                                                || request.relationshipIds()
                                                        .contains(relationship.id()))
                        .toList();
        DatasetRelationshipGraph graph =
                new DatasetRelationshipGraph(
                        relationships.stream()
                                .map(
                                        relationship ->
                                                new DatasetRelationshipGraph.RelationshipEdge(
                                                        relationship.id(),
                                                        relationship.sourceDatasetId(),
                                                        relationship.targetDatasetId(),
                                                        relationship.cardinality()))
                                .toList());
        Map<UUID, RelationshipDefinition> byId =
                relationships.stream()
                        .collect(Collectors.toMap(RelationshipDefinition::id, Function.identity()));

        Map<UUID, List<QueryPlan.MetricProjection>> metricsByDataset = new LinkedHashMap<>();
        metrics.forEach(
                metric ->
                        metricsByDataset
                                .computeIfAbsent(
                                        metric.definition().dataset().getId(),
                                        ignored -> new ArrayList<>())
                                .add(metric));
        List<QueryPlan.DatasetAggregatePlan> aggregates = new ArrayList<>();
        for (Map.Entry<UUID, List<QueryPlan.MetricProjection>> entry :
                metricsByDataset.entrySet()) {
            Map<UUID, QueryPlan.ResolvedPath> paths = new LinkedHashMap<>();
            for (QueryPlan.DimensionProjection dimension : dimensions) {
                UUID target = dimension.definition().dataset().getId();
                if (entry.getKey().equals(target) || paths.containsKey(target)) {
                    continue;
                }
                DatasetRelationshipGraph.PathResolution resolution =
                        graph.resolve(entry.getKey(), target, request.relationshipIds());
                if (resolution instanceof DatasetRelationshipGraph.MissingPath) {
                    throw new IllegalArgumentException(
                            "no non-expanding relationship path to dimension "
                                    + dimension.definition().dimension().getId());
                }
                if (resolution instanceof DatasetRelationshipGraph.AmbiguousPaths) {
                    throw new IllegalArgumentException(
                            "ambiguous relationship path to dimension "
                                    + dimension.definition().dimension().getId()
                                    + "; provide relationshipIds");
                }
                List<RelationshipDefinition> path =
                        ((DatasetRelationshipGraph.UniquePath) resolution)
                                .edges().stream().map(edge -> byId.get(edge.id())).toList();
                paths.put(target, new QueryPlan.ResolvedPath(target, path));
            }
            aggregates.add(
                    new QueryPlan.DatasetAggregatePlan(
                            entry.getKey(),
                            new ArrayList<>(paths.values()),
                            dimensions,
                            entry.getValue()));
        }
        return new QueryPlan(dimensions, metrics, aggregates);
    }

    private static QueryPlan.DimensionProjection dimension(
            QueryDtos.DimensionSelection selection,
            SemanticCatalogReader.DimensionDefinition definition) {
        DimensionType type = DimensionType.valueOf(definition.dimension().getDimensionType());
        if (type == DimensionType.STANDARD && selection.granularity() != null) {
            throw new IllegalArgumentException("STANDARD dimension cannot use a time granularity");
        }
        if (type == DimensionType.TIME) {
            if (selection.granularity() == null) {
                throw new IllegalArgumentException("TIME dimension requires a granularity");
            }
            if (!definition.allowedGranularities().contains(selection.granularity())) {
                throw new IllegalArgumentException("time granularity is not allowed for dimension");
            }
        }
        return new QueryPlan.DimensionProjection(definition, selection.granularity());
    }
}
