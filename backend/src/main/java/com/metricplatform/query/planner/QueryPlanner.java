package com.metricplatform.query.planner;

import com.metricplatform.query.QueryDtos;
import com.metricplatform.query.QueryValidationException;
import com.metricplatform.query.SemanticCatalogReader;
import com.metricplatform.query.SemanticCatalogReader.DimensionDefinition;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.semantic.SemanticDtos.DimensionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueryPlanner {
    private final SemanticCatalogReader catalog;

    public QueryPlan plan(QueryDtos.QueryRequest request) {
        if (request.metricIds() == null || request.metricIds().isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        List<QueryPlan.MetricProjection> metrics = request.metricIds().stream()
                .distinct()
                .map(catalog::metric)
                .map(QueryPlan.MetricProjection::new)
                .toList();
        List<QueryPlan.DimensionProjection> dimensions = request.dimensions().stream()
                .map(selection -> dimension(selection, catalog.dimension(selection.dimensionId())))
                .toList();
        List<QueryPlan.FilterProjection> filters = request.filters().stream()
                .map(filter -> filter(filter, catalog.dimension(filter.dimensionId())))
                .toList();
        QueryPlan.TimeProjection timeRange = request.timeRange() == null
                ? null
                : timeRange(request.timeRange(), catalog.dimension(request.timeRange().dimensionId()));

        UUID dataSourceId = metrics.getFirst().definition().dataset().getDataSourceId();
        List<DimensionDefinition> referencedDimensions = new ArrayList<>();
        dimensions.forEach(value -> referencedDimensions.add(value.definition()));
        filters.forEach(value -> referencedDimensions.add(value.definition()));
        if (timeRange != null) referencedDimensions.add(timeRange.definition());
        if (metrics.stream().anyMatch(metric -> !dataSourceId.equals(
                        metric.definition().dataset().getDataSourceId()))
                || referencedDimensions.stream().anyMatch(dimension -> !dataSourceId.equals(
                        dimension.dataset().getDataSourceId()))) {
            throw new IllegalArgumentException("all semantic objects must use the same data source");
        }
        validateSort(request.sort(), metrics, dimensions);

        List<RelationshipDefinition> relationships = catalog.relationships().stream()
                .filter(relationship -> request.relationshipIds().isEmpty()
                        || request.relationshipIds().contains(relationship.id()))
                .toList();
        DatasetRelationshipGraph graph = new DatasetRelationshipGraph(
                relationships.stream()
                        .map(relationship -> new DatasetRelationshipGraph.RelationshipEdge(
                                relationship.id(), relationship.sourceDatasetId(),
                                relationship.targetDatasetId(), relationship.cardinality()))
                        .toList());
        Map<UUID, RelationshipDefinition> byId = relationships.stream()
                .collect(Collectors.toMap(RelationshipDefinition::id, Function.identity()));

        Map<UUID, List<QueryPlan.MetricProjection>> metricsByDataset = new LinkedHashMap<>();
        metrics.forEach(metric -> metricsByDataset
                .computeIfAbsent(metric.definition().dataset().getId(), ignored -> new ArrayList<>())
                .add(metric));
        Set<DimensionDefinition> requiredDimensions = new LinkedHashSet<>(referencedDimensions);
        List<QueryPlan.DatasetAggregatePlan> aggregates = new ArrayList<>();
        for (Map.Entry<UUID, List<QueryPlan.MetricProjection>> entry : metricsByDataset.entrySet()) {
            Map<UUID, QueryPlan.ResolvedPath> paths = new LinkedHashMap<>();
            for (DimensionDefinition dimension : requiredDimensions) {
                UUID target = dimension.dataset().getId();
                if (entry.getKey().equals(target) || paths.containsKey(target)) continue;
                DatasetRelationshipGraph.PathResolution resolution =
                        graph.resolve(entry.getKey(), target, request.relationshipIds());
                if (resolution instanceof DatasetRelationshipGraph.MissingPath) {
                    throw new QueryValidationException(
                            "RELATIONSHIP_PATH_NOT_FOUND",
                            "no non-expanding relationship path to dimension "
                                    + dimension.dimension().getId(),
                            QueryDtos.SemanticObjectType.DIMENSION,
                            dimension.dimension().getId(),
                            List.of());
                }
                if (resolution instanceof DatasetRelationshipGraph.AmbiguousPaths ambiguous) {
                    List<QueryDtos.RelationshipPathSummary> candidates = ambiguous.paths().stream()
                            .flatMap(List::stream)
                            .distinct()
                            .map(edge -> relationshipSummary(byId.get(edge.id())))
                            .toList();
                    throw new QueryValidationException(
                            "AMBIGUOUS_RELATIONSHIP_PATH",
                            "ambiguous relationship path to dimension "
                                    + dimension.dimension().getId() + "; provide relationshipIds",
                            QueryDtos.SemanticObjectType.DIMENSION,
                            dimension.dimension().getId(),
                            candidates);
                }
                List<RelationshipDefinition> path = ((DatasetRelationshipGraph.UniquePath) resolution)
                        .edges().stream().map(edge -> byId.get(edge.id())).toList();
                paths.put(target, new QueryPlan.ResolvedPath(target, path));
            }
            aggregates.add(new QueryPlan.DatasetAggregatePlan(
                    entry.getKey(), new ArrayList<>(paths.values()), dimensions, entry.getValue()));
        }
        return new QueryPlan(dimensions, metrics, aggregates, filters, timeRange, request.sort(),
                request.limit());
    }

    private static QueryPlan.DimensionProjection dimension(
            QueryDtos.DimensionSelection selection, DimensionDefinition definition) {
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

    private static QueryPlan.FilterProjection filter(
            QueryDtos.DimensionFilter filter, DimensionDefinition definition) {
        int size = filter.values().size();
        switch (filter.operator()) {
            case IS_NULL, IS_NOT_NULL -> requireArity(filter, size == 0, "requires no values");
            case BETWEEN -> requireArity(filter, size == 2, "requires exactly two values");
            case IN -> requireArity(filter, size > 0, "requires at least one value");
            default -> requireArity(filter, size == 1, "requires exactly one value");
        }
        String type = baseType(definition.field().getStarrocksType());
        if (filter.operator() == QueryDtos.FilterOperator.CONTAINS
                && !Set.of("CHAR", "VARCHAR", "STRING", "TEXT").contains(type)) {
            throw new IllegalArgumentException("CONTAINS requires a text dimension");
        }
        return new QueryPlan.FilterProjection(definition, filter.operator(), filter.values());
    }

    private static void requireArity(
            QueryDtos.DimensionFilter filter, boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(filter.operator() + " " + message);
    }

    private static QueryPlan.TimeProjection timeRange(
            QueryDtos.TimeRange request, DimensionDefinition definition) {
        if (DimensionType.valueOf(definition.dimension().getDimensionType()) != DimensionType.TIME) {
            throw new IllegalArgumentException("timeRange requires a TIME dimension");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timezone());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeRange timezone is invalid", exception);
        }
        boolean absolute = request.start() != null || request.end() != null;
        if (absolute == (request.preset() != null)) {
            throw new IllegalArgumentException(
                    "timeRange requires either start/end or preset, but not both");
        }
        Instant start;
        Instant end;
        if (absolute) {
            if (request.start() == null || request.end() == null
                    || !request.end().isAfter(request.start())) {
                throw new IllegalArgumentException("timeRange requires start before end");
            }
            start = request.start();
            end = request.end();
        } else {
            Instant[] range = preset(request.preset(), zone);
            start = range[0];
            end = range[1];
        }
        return new QueryPlan.TimeProjection(
                definition, start, end, zone.getId(), request.preset());
    }

    private static Instant[] preset(QueryDtos.TimePreset preset, ZoneId zone) {
        ZonedDateTime today = LocalDate.now(zone).atStartOfDay(zone);
        ZonedDateTime start;
        ZonedDateTime end = today.plusDays(1);
        switch (preset) {
            case TODAY -> { start = today; end = today.plusDays(1); }
            case YESTERDAY -> { start = today.minusDays(1); end = today; }
            case LAST_7_DAYS -> start = today.minusDays(6);
            case LAST_30_DAYS -> start = today.minusDays(29);
            case MONTH_TO_DATE -> start = today.withDayOfMonth(1);
            case QUARTER_TO_DATE -> start = today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
            case YEAR_TO_DATE -> start = today.withDayOfYear(1);
            case LAST_MONTH -> {
                start = today.minusMonths(1).withDayOfMonth(1);
                end = today.withDayOfMonth(1);
            }
            case LAST_QUARTER -> {
                ZonedDateTime current = today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
                start = current.minusMonths(3);
                end = current;
            }
            case LAST_YEAR -> {
                start = today.minusYears(1).with(TemporalAdjusters.firstDayOfYear());
                end = today.with(TemporalAdjusters.firstDayOfYear());
            }
            default -> throw new IllegalArgumentException("unsupported time preset");
        }
        return new Instant[] {start.toInstant(), end.toInstant()};
    }

    private static void validateSort(
            List<QueryDtos.SortSelection> sort,
            List<QueryPlan.MetricProjection> metrics,
            List<QueryPlan.DimensionProjection> dimensions) {
        Set<UUID> metricIds = metrics.stream()
                .map(value -> value.definition().metric().getId()).collect(Collectors.toSet());
        Set<UUID> dimensionIds = dimensions.stream()
                .map(value -> value.definition().dimension().getId()).collect(Collectors.toSet());
        for (QueryDtos.SortSelection selection : sort) {
            boolean selected = selection.type() == QueryDtos.SortTargetType.METRIC
                    ? metricIds.contains(selection.objectId())
                    : dimensionIds.contains(selection.objectId());
            if (!selected) throw new IllegalArgumentException("sort target must be selected");
        }
    }

    private static String baseType(String type) {
        if (type == null) return "";
        int parameters = type.indexOf('(');
        return (parameters < 0 ? type : type.substring(0, parameters))
                .trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static QueryDtos.RelationshipPathSummary relationshipSummary(
            RelationshipDefinition relationship) {
        String source = relationship.sourceDataset() == null
                ? relationship.sourceDatasetId().toString()
                : relationship.sourceDataset().getName();
        String target = relationship.targetDataset() == null
                ? relationship.targetDatasetId().toString()
                : relationship.targetDataset().getName();
        return new QueryDtos.RelationshipPathSummary(
                relationship.id(),
                source + " -> " + target,
                source + " -> " + target,
                relationship.sourceDatasetId(),
                relationship.targetDatasetId(),
                relationship.cardinality().name(),
                null,
                true);
    }
}
