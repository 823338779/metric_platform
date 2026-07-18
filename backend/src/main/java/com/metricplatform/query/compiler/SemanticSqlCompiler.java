package com.metricplatform.query.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.query.IdentifierQuoter;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.query.planner.QueryPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SemanticSqlCompiler {
    private final ObjectMapper objectMapper;
    private final FilterSqlCompiler filters;

    public SemanticSqlCompiler(ObjectMapper objectMapper, FilterSqlCompiler filters) {
        this.objectMapper = objectMapper;
        this.filters = filters;
    }

    public CompiledQuery compile(QueryPlan plan) {
        if (plan.aggregates().isEmpty())
            throw new IllegalArgumentException("query plan has no aggregates");
        List<String> ctes = new ArrayList<>();
        List<CompiledQuery.SqlParameter> parameters = new ArrayList<>();
        Map<UUID, Integer> metricIndexes = new HashMap<>();
        for (int index = 0; index < plan.metrics().size(); index++) {
            metricIndexes.put(plan.metrics().get(index).definition().metric().getId(), index);
        }
        for (int index = 0; index < plan.aggregates().size(); index++) {
            ctes.add(
                    q("agg_" + index)
                            + " AS ("
                            + compileAggregate(
                                    plan.aggregates().get(index), plan, metricIndexes, parameters)
                            + ")");
        }
        return new CompiledQuery(
                "WITH " + String.join(", ", ctes) + " " + compileFinal(plan), parameters);
    }

    private String compileAggregate(
            QueryPlan.DatasetAggregatePlan aggregate,
            QueryPlan plan,
            Map<UUID, Integer> metricIndexes,
            List<CompiledQuery.SqlParameter> parameters) {
        DatasetEntity fact = aggregate.metrics().getFirst().definition().dataset();
        Map<UUID, String> aliases = new LinkedHashMap<>();
        Map<UUID, DatasetEntity> datasets = new LinkedHashMap<>();
        aliases.put(fact.getId(), "d0");
        datasets.put(fact.getId(), fact);
        List<RelationshipDefinition> joins =
                aggregate.paths().stream()
                        .flatMap(path -> path.relationships().stream())
                        .distinct()
                        .toList();
        int aliasIndex = 1;
        for (RelationshipDefinition relationship : joins) {
            if (!aliases.containsKey(relationship.sourceDatasetId())) {
                throw new IllegalArgumentException(
                        "relationship path is not connected to fact dataset");
            }
            if (!aliases.containsKey(relationship.targetDatasetId())) {
                aliases.put(relationship.targetDatasetId(), "d" + aliasIndex++);
            }
            if (relationship.sourceDataset() != null)
                datasets.put(relationship.sourceDatasetId(), relationship.sourceDataset());
            if (relationship.targetDataset() != null)
                datasets.put(relationship.targetDatasetId(), relationship.targetDataset());
        }
        aggregate
                .dimensions()
                .forEach(
                        dimension ->
                                datasets.put(
                                        dimension.definition().dataset().getId(),
                                        dimension.definition().dataset()));

        List<String> select = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        for (int index = 0; index < plan.dimensions().size(); index++) {
            QueryPlan.DimensionProjection dimension = plan.dimensions().get(index);
            String expression = dimensionExpression(dimension, aliases);
            select.add(expression + " AS " + q("d_" + index));
            groupBy.add(expression);
        }
        for (QueryPlan.MetricProjection metric : aggregate.metrics()) {
            int index = metricIndexes.get(metric.definition().metric().getId());
            select.add(
                    metricExpression(metric, aliases.get(aggregate.datasetId()))
                            + " AS "
                            + q("m_" + index));
        }

        StringBuilder sql =
                new StringBuilder("SELECT ")
                        .append(String.join(", ", select))
                        .append(" FROM ")
                        .append(table(fact))
                        .append(" ")
                        .append(q("d0"));
        for (RelationshipDefinition relationship : joins) {
            DatasetEntity target = datasets.get(relationship.targetDatasetId());
            if (target == null)
                throw new IllegalArgumentException("relationship target metadata is missing");
            String sourceAlias = aliases.get(relationship.sourceDatasetId());
            String targetAlias = aliases.get(relationship.targetDatasetId());
            String conditions =
                    String.join(
                            " AND ",
                            relationship.fieldPairs().stream()
                                    .map(
                                            pair ->
                                                    column(sourceAlias, pair.sourceField())
                                                            + " = "
                                                            + column(
                                                                    targetAlias,
                                                                    pair.targetField()))
                                    .toList());
            if (conditions.isBlank())
                throw new IllegalArgumentException("relationship has no field pairs");
            sql.append(" LEFT JOIN ")
                    .append(table(target))
                    .append(" ")
                    .append(q(targetAlias))
                    .append(" ON ")
                    .append(conditions);
        }
        FilterSqlCompiler.CompiledPredicate predicate = compileFilter(fact, aggregate, "d0");
        if (!predicate.sql().isBlank()) {
            sql.append(" WHERE ").append(predicate.sql());
            parameters.addAll(predicate.parameters());
        }
        if (!groupBy.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        return sql.toString();
    }

    private FilterSqlCompiler.CompiledPredicate compileFilter(
            DatasetEntity dataset, QueryPlan.DatasetAggregatePlan aggregate, String alias) {
        if (dataset.getFilterExpression() == null || dataset.getFilterExpression().isBlank()) {
            return new FilterSqlCompiler.CompiledPredicate("", List.of());
        }
        try {
            FilterExpression expression =
                    objectMapper.readValue(dataset.getFilterExpression(), FilterExpression.class);
            Map<UUID, DatasetFieldEntity> fieldsById = new HashMap<>();
            aggregate.metrics().stream()
                    .map(metric -> metric.definition().field())
                    .filter(java.util.Objects::nonNull)
                    .forEach(field -> fieldsById.put(field.getId(), field));
            aggregate.dimensions().stream()
                    .map(dimension -> dimension.definition().field())
                    .filter(field -> dataset.getId().equals(field.getDatasetId()))
                    .forEach(field -> fieldsById.put(field.getId(), field));
            aggregate.paths().stream()
                    .flatMap(path -> path.relationships().stream())
                    .flatMap(relationship -> relationship.fieldPairs().stream())
                    .forEach(
                            pair -> {
                                if (dataset.getId().equals(pair.sourceField().getDatasetId()))
                                    fieldsById.put(pair.sourceField().getId(), pair.sourceField());
                                if (dataset.getId().equals(pair.targetField().getDatasetId()))
                                    fieldsById.put(pair.targetField().getId(), pair.targetField());
                            });
            return filters.compile(expression, fieldsById, alias);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("dataset filter is not valid", exception);
        }
    }

    private String compileFinal(QueryPlan plan) {
        List<String> select = new ArrayList<>();
        for (int dimensionIndex = 0; dimensionIndex < plan.dimensions().size(); dimensionIndex++) {
            List<String> candidates = new ArrayList<>();
            for (int aggregateIndex = 0;
                    aggregateIndex < plan.aggregates().size();
                    aggregateIndex++) {
                candidates.add(column("agg_" + aggregateIndex, "d_" + dimensionIndex));
            }
            String value =
                    candidates.size() == 1
                            ? candidates.getFirst()
                            : "COALESCE(" + String.join(", ", candidates) + ")";
            select.add(
                    value
                            + " AS "
                            + q(
                                    plan.dimensions()
                                            .get(dimensionIndex)
                                            .definition()
                                            .dimension()
                                            .getName()));
        }
        for (int metricIndex = 0; metricIndex < plan.metrics().size(); metricIndex++) {
            int aggregateIndex = aggregateIndexForMetric(plan, plan.metrics().get(metricIndex));
            select.add(
                    column("agg_" + aggregateIndex, "m_" + metricIndex)
                            + " AS "
                            + q(plan.metrics().get(metricIndex).definition().metric().getName()));
        }
        StringBuilder sql =
                new StringBuilder("SELECT ")
                        .append(String.join(", ", select))
                        .append(" FROM ")
                        .append(q("agg_0"));
        for (int index = 1; index < plan.aggregates().size(); index++) {
            if (plan.dimensions().isEmpty()) {
                sql.append(" CROSS JOIN ").append(q("agg_" + index));
            } else {
                sql.append(" FULL OUTER JOIN ").append(q("agg_" + index)).append(" ON ");
                List<String> conditions = new ArrayList<>();
                for (int dimension = 0; dimension < plan.dimensions().size(); dimension++) {
                    List<String> prior = new ArrayList<>();
                    for (int priorIndex = 0; priorIndex < index; priorIndex++) {
                        prior.add(column("agg_" + priorIndex, "d_" + dimension));
                    }
                    String left =
                            prior.size() == 1
                                    ? prior.getFirst()
                                    : "COALESCE(" + String.join(", ", prior) + ")";
                    conditions.add(left + " = " + column("agg_" + index, "d_" + dimension));
                }
                sql.append(String.join(" AND ", conditions));
            }
        }
        return sql.toString();
    }

    private static int aggregateIndexForMetric(QueryPlan plan, QueryPlan.MetricProjection metric) {
        for (int index = 0; index < plan.aggregates().size(); index++) {
            if (plan.aggregates().get(index).metrics().contains(metric)) return index;
        }
        throw new IllegalArgumentException("metric is missing from aggregate plan");
    }

    private static String dimensionExpression(
            QueryPlan.DimensionProjection projection, Map<UUID, String> aliases) {
        String alias = aliases.get(projection.definition().dataset().getId());
        if (alias == null) throw new IllegalArgumentException("dimension dataset is not joined");
        String column = column(alias, projection.definition().field());
        if (projection.granularity() == null) return column;
        return "date_trunc('"
                + projection.granularity().name().toLowerCase(java.util.Locale.ROOT)
                + "', "
                + column
                + ")";
    }

    private static String metricExpression(QueryPlan.MetricProjection projection, String alias) {
        String aggregation = projection.definition().metric().getAggregation();
        if (projection.definition().field() == null) return "COUNT(*)";
        return aggregation + "(" + column(alias, projection.definition().field()) + ")";
    }

    private static String table(DatasetEntity dataset) {
        return q(dataset.getSchemaName()) + "." + q(dataset.getObjectName());
    }

    private static String column(String alias, DatasetFieldEntity field) {
        return column(alias, field.getPhysicalName());
    }

    private static String column(String alias, String name) {
        return q(alias) + "." + q(name);
    }

    private static String q(String value) {
        return IdentifierQuoter.quote(value);
    }
}
