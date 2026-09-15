package com.metricplatform.query.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.query.IdentifierQuoter;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.query.planner.QueryPlan;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SemanticSqlCompiler {
    private static final String COUNT_DISTINCT = "COUNT_DISTINCT";
    private static final String COUNT_DISTINCT_SQL = "COUNT(DISTINCT ";
    private static final String CASE_WHEN_SQL = "CASE WHEN ";
    private static final String THEN_SQL = " THEN ";
    private static final String END_SQL = " END";
    private static final String LEFT_JOIN_TYPE = "LEFT";
    private static final String INNER_JOIN_TYPE = "INNER";
    private static final String LEFT_JOIN_SQL = " LEFT JOIN ";
    private static final String INNER_JOIN_SQL = " INNER JOIN ";
    private final ObjectMapper objectMapper;
    private final FilterSqlCompiler filters;

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
        plan.filters().forEach(
                filter -> datasets.put(
                        filter.definition().dataset().getId(), filter.definition().dataset()));
        if (plan.timeRange() != null) {
            datasets.put(
                    plan.timeRange().definition().dataset().getId(),
                    plan.timeRange().definition().dataset());
        }

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
                    metricExpression(metric, aliases.get(aggregate.datasetId()), parameters)
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
            sql.append(joinSql(relationship.joinType()))
                    .append(table(target))
                    .append(" ")
                    .append(q(targetAlias))
                    .append(" ON ")
                    .append(conditions);
        }
        List<String> predicates = new ArrayList<>();
        FilterSqlCompiler.CompiledPredicate fixed = compileFilter(fact, aggregate, "d0");
        if (!fixed.sql().isBlank()) {
            predicates.add(fixed.sql());
            parameters.addAll(fixed.parameters());
        }
        predicates.addAll(compileRuntimePredicates(plan, aliases, parameters));
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (!groupBy.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        return sql.toString();
    }

    private static List<String> compileRuntimePredicates(
            QueryPlan plan,
            Map<UUID, String> aliases,
            List<CompiledQuery.SqlParameter> parameters) {
        List<String> predicates = new ArrayList<>();
        for (QueryPlan.FilterProjection filter : plan.filters()) {
            String alias = aliases.get(filter.definition().dataset().getId());
            if (alias == null) throw new IllegalArgumentException("filter dimension dataset is not joined");
            String expression = column(alias, filter.definition().field());
            int jdbcType = jdbcType(filter.definition().field().getStarrocksType());
            switch (filter.operator()) {
                case IS_NULL -> predicates.add(expression + " IS NULL");
                case IS_NOT_NULL -> predicates.add(expression + " IS NOT NULL");
                case BETWEEN -> {
                    predicates.add(expression + " BETWEEN ? AND ?");
                    add(parameters, jdbcType, filter.values().get(0));
                    add(parameters, jdbcType, filter.values().get(1));
                }
                case IN -> {
                    predicates.add(expression + " IN ("
                            + String.join(", ", java.util.Collections.nCopies(filter.values().size(), "?"))
                            + ")");
                    filter.values().forEach(value -> add(parameters, jdbcType, value));
                }
                case CONTAINS -> {
                    predicates.add(expression + " LIKE ?");
                    add(parameters, Types.VARCHAR, "%" + filter.values().getFirst() + "%");
                }
                default -> {
                    predicates.add(expression + " " + operator(filter.operator()) + " ?");
                    add(parameters, jdbcType, filter.values().getFirst());
                }
            }
        }
        if (plan.timeRange() != null) {
            QueryPlan.TimeProjection time = plan.timeRange();
            String alias = aliases.get(time.definition().dataset().getId());
            if (alias == null) throw new IllegalArgumentException("time dimension dataset is not joined");
            String expression = column(alias, time.definition().field());
            predicates.add(expression + " >= ? AND " + expression + " < ?");
            add(parameters, Types.TIMESTAMP, Timestamp.from(time.startInclusive()));
            add(parameters, Types.TIMESTAMP, Timestamp.from(time.endExclusive()));
        }
        return predicates;
    }

    private static String operator(com.metricplatform.query.QueryDtos.FilterOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new IllegalArgumentException("filter operator cannot be compiled here");
        };
    }

    private static void add(
            List<CompiledQuery.SqlParameter> parameters, int jdbcType, Object value) {
        parameters.add(new CompiledQuery.SqlParameter(jdbcType, value));
    }

    private static int jdbcType(String type) {
        String normalized = type == null ? "" : type.toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("DATE") && !normalized.startsWith("DATETIME")) return Types.DATE;
        if (normalized.startsWith("TIMESTAMP") || normalized.startsWith("DATETIME")) return Types.TIMESTAMP;
        if (normalized.startsWith("BOOL")) return Types.BOOLEAN;
        if (normalized.matches("^(TINYINT|SMALLINT|INT|INTEGER|BIGINT|LARGEINT).*$")) return Types.BIGINT;
        if (normalized.matches("^(FLOAT|DOUBLE|DECIMAL).*$")) return Types.DECIMAL;
        return Types.VARCHAR;
    }

    private static String sortExpression(
            QueryPlan plan, com.metricplatform.query.QueryDtos.SortSelection selection) {
        String name;
        if (selection.type() == com.metricplatform.query.QueryDtos.SortTargetType.METRIC) {
            name = plan.metrics().stream()
                    .filter(value -> value.definition().metric().getId().equals(selection.objectId()))
                    .findFirst().orElseThrow().definition().metric().getName();
        } else {
            name = plan.dimensions().stream()
                    .filter(value -> value.definition().dimension().getId().equals(selection.objectId()))
                    .findFirst().orElseThrow().definition().dimension().getName();
        }
        return q(name) + " " + selection.direction().name();
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
        if (!plan.sort().isEmpty()) {
            sql.append(" ORDER BY ")
                    .append(
                            String.join(
                                    ", ",
                                    plan.sort().stream()
                                            .map(value -> sortExpression(plan, value))
                                            .toList()));
        }
        sql.append(" LIMIT ").append(plan.limit() + 1);
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

    private String metricExpression(
            QueryPlan.MetricProjection projection,
            String alias,
            List<CompiledQuery.SqlParameter> parameters) {
        String aggregation = projection.definition().metric().getAggregation();
        String fixedFilter = projection.definition().metric().getFixedFilterExpression();
        boolean filtered = fixedFilter != null && !fixedFilter.isBlank();
        if (projection.definition().field() == null) {
            if (filtered)
                throw new IllegalArgumentException(
                        "metric fixed filter requires an aggregate field");
            return "COUNT(*)";
        }
        String operand = column(alias, projection.definition().field());
        if (filtered) {
            try {
                FilterExpression expression =
                        // 固定口径必须无损读取十进制常量，不能先转 double 再绑定 DECIMAL。
                        objectMapper
                                .readerFor(FilterExpression.class)
                                .with(
                                        com.fasterxml.jackson.databind.DeserializationFeature
                                                .USE_BIG_DECIMAL_FOR_FLOATS)
                                .readValue(fixedFilter);
                var predicate =
                        filters.compile(expression, projection.definition().filterFields(), alias);
                if (predicate.sql().isBlank())
                    throw new IllegalArgumentException("metric fixed filter is empty");
                // 固定条件仅限制当前聚合的输入，不能进入 WHERE 影响同查询中的其他指标。
                operand = CASE_WHEN_SQL + predicate.sql() + THEN_SQL + operand + END_SQL;
                // SELECT 占位符先于 WHERE；必须按 SQL 文本生成顺序追加绑定参数。
                parameters.addAll(predicate.parameters());
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("metric fixed filter is not valid", exception);
            }
        }
        return (COUNT_DISTINCT.equals(aggregation) ? COUNT_DISTINCT_SQL : aggregation + "(")
                + operand
                + ")";
    }

    private static String joinSql(String joinType) {
        // 连接类型只能来自受限枚举，不能把持久化字符串直接作为 SQL 片段拼接。
        return switch (joinType) {
            case LEFT_JOIN_TYPE -> LEFT_JOIN_SQL;
            case INNER_JOIN_TYPE -> INNER_JOIN_SQL;
            default -> throw new IllegalArgumentException("unsupported relationship join type");
        };
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
