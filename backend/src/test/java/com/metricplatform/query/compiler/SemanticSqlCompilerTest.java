package com.metricplatform.query.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.query.SemanticCatalogReader;
import com.metricplatform.query.planner.DatasetRelationshipGraph;
import com.metricplatform.query.planner.QueryPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticSqlCompilerTest {
    @Test
    void keepsStoredFixedFilterDecimalPrecision() throws Exception {
        var dataset = dataset("dw", "orders");
        var field = field(dataset, "amount");
        var metric = filteredMetric(dataset, "sales", "amount", "SUM", field, "placeholder");
        metric.definition()
                .metric()
                .setFixedFilterExpression(
                        predicate(field, "placeholder")
                                .replace("\"placeholder\"", "12345678901234567890.123456789"));
        var plan =
                new QueryPlan(
                        List.of(),
                        List.of(metric),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        dataset.getId(), List.of(), List.of(), List.of(metric))));
        assertThat(compiler().compile(plan).parameters())
                .containsExactly(
                        new CompiledQuery.SqlParameter(
                                java.sql.Types.DECIMAL,
                                new java.math.BigDecimal("12345678901234567890.123456789")));
    }

    @Test
    void keepsMetricFiltersInsideEachAggregateAndBindsSelectBeforeWhere() throws Exception {
        DatasetEntity orders = dataset("dw", "orders");
        QueryPlan.DimensionProjection status = dimension(orders, "status", "status");
        DatasetFieldEntity statusField = status.definition().field();
        QueryPlan.MetricProjection paid =
                filteredMetric(orders, "paid", "amount", "SUM", statusField, "PAID");
        QueryPlan.MetricProjection refunds =
                filteredMetric(
                        orders,
                        "refunds",
                        "customer_id",
                        "COUNT_DISTINCT",
                        statusField,
                        "REFUNDED");
        orders.setFilterExpression(predicate(statusField, "ACTIVE"));
        QueryPlan plan =
                new QueryPlan(
                        List.of(status),
                        List.of(paid, refunds),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        orders.getId(),
                                        List.of(),
                                        List.of(status),
                                        List.of(paid, refunds))),
                        List.of(
                                new QueryPlan.FilterProjection(
                                        status.definition(),
                                        QueryDtos.FilterOperator.NE,
                                        List.of("DELETED"))),
                        null,
                        List.of(),
                        10);

        CompiledQuery compiled = compiler().compile(plan);

        assertThat(compiled.sql())
                .contains(
                        "SUM(CASE WHEN `d0`.`status` = ? THEN `d0`.`amount` END) AS `m_0`",
                        "COUNT(DISTINCT CASE WHEN `d0`.`status` = ? THEN `d0`.`customer_id` END) AS `m_1`",
                        " WHERE `d0`.`status` = ? AND `d0`.`status` <> ? GROUP BY");
        assertThat(compiled.parameters())
                .extracting(CompiledQuery.SqlParameter::value)
                .containsExactly("PAID", "REFUNDED", "ACTIVE", "DELETED");
        assertThat(compiled.sql()).doesNotContain("PAID", "REFUNDED");
    }

    @Test
    void preservesExplicitInnerJoin() {
        DatasetEntity orders = dataset("dw", "orders");
        DatasetEntity regions = dataset("dw", "regions");
        QueryPlan.DimensionProjection region = dimension(regions, "region", "name");
        QueryPlan.MetricProjection sales = metric(orders, "sales", "amount", "SUM");
        var old = path(orders, regions, "region_id", "id").relationships().getFirst();
        var inner =
                new SemanticCatalogReader.RelationshipDefinition(
                        old.id(),
                        old.sourceDatasetId(),
                        old.targetDatasetId(),
                        old.cardinality(),
                        old.fieldPairs(),
                        orders,
                        regions,
                        "INNER");
        QueryPlan plan =
                new QueryPlan(
                        List.of(region),
                        List.of(sales),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        orders.getId(),
                                        List.of(
                                                new QueryPlan.ResolvedPath(
                                                        regions.getId(), List.of(inner))),
                                        List.of(region),
                                        List.of(sales))));

        assertThat(compiler().compile(plan).sql())
                .contains(" INNER JOIN `dw`.`regions` `d1` ON ")
                .doesNotContain(" LEFT JOIN ");
    }

    @Test
    void compilesDistinctCountUsingSqlDistinctSyntax() {
        DatasetEntity orders = dataset("dw", "orders");
        QueryPlan.MetricProjection customers =
                metric(orders, "customers", "customer_id", "COUNT_DISTINCT");
        QueryPlan plan =
                new QueryPlan(
                        List.of(),
                        List.of(customers),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        orders.getId(), List.of(), List.of(), List.of(customers))));

        assertThat(compiler().compile(plan).sql())
                .contains("COUNT(DISTINCT `d0`.`customer_id`) AS `m_0`")
                .doesNotContain("COUNT_DISTINCT(");
    }

    @Test
    void compilesTwoFactAggregatesAndFullOuterJoinsDimensionKeys() {
        DatasetEntity orders = dataset("dw", "orders");
        DatasetEntity tickets = dataset("dw", "tickets");
        DatasetEntity regions = dataset("dw", "regions");
        QueryPlan.DimensionProjection region = dimension(regions, "region", "name");
        QueryPlan.MetricProjection sales = metric(orders, "sales", "revenue", "SUM");
        QueryPlan.MetricProjection count = metric(tickets, "tickets", null, "COUNT");
        QueryPlan.ResolvedPath orderPath = path(orders, regions, "region_id", "id");
        QueryPlan.ResolvedPath ticketPath = path(tickets, regions, "region_id", "id");
        QueryPlan plan =
                new QueryPlan(
                        List.of(region),
                        List.of(sales, count),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        orders.getId(),
                                        List.of(orderPath),
                                        List.of(region),
                                        List.of(sales)),
                                new QueryPlan.DatasetAggregatePlan(
                                        tickets.getId(),
                                        List.of(ticketPath),
                                        List.of(region),
                                        List.of(count))));

        String sql = compiler().compile(plan).sql();

        assertThat(sql)
                .contains(
                        "`agg_0` AS (",
                        "`agg_1` AS (",
                        " LEFT JOIN `dw`.`regions` `d1` ON ",
                        "FULL OUTER JOIN `agg_1`",
                        "COALESCE(`agg_0`.`d_0`, `agg_1`.`d_0`) AS `region`");
        assertThat(sql).contains("SUM(`d0`.`revenue`) AS `m_0`", "COUNT(*) AS `m_1`");
    }

    @Test
    void combinesScalarAggregatesWithoutAKeyedJoin() {
        DatasetEntity orders = dataset("dw", "orders");
        DatasetEntity tickets = dataset("dw", "tickets");
        QueryPlan.MetricProjection sales = metric(orders, "sales", "revenue", "SUM");
        QueryPlan.MetricProjection count = metric(tickets, "tickets", null, "COUNT");
        QueryPlan plan =
                new QueryPlan(
                        List.of(),
                        List.of(sales, count),
                        List.of(
                                new QueryPlan.DatasetAggregatePlan(
                                        orders.getId(), List.of(), List.of(), List.of(sales)),
                                new QueryPlan.DatasetAggregatePlan(
                                        tickets.getId(), List.of(), List.of(), List.of(count))));

        assertThat(compiler().compile(plan).sql()).contains("FROM `agg_0` CROSS JOIN `agg_1`");
    }

    private static SemanticSqlCompiler compiler() {
        return new SemanticSqlCompiler(new ObjectMapper(), new FilterSqlCompiler());
    }

    private static QueryPlan.MetricProjection filteredMetric(
            DatasetEntity dataset,
            String name,
            String column,
            String aggregation,
            DatasetFieldEntity filterField,
            String value)
            throws Exception {
        var projection = metric(dataset, name, column, aggregation);
        var definition = projection.definition();
        definition.metric().setFixedFilterExpression(predicate(filterField, value));
        return new QueryPlan.MetricProjection(
                new SemanticCatalogReader.MetricDefinition(
                        definition.metric(),
                        dataset,
                        definition.field(),
                        Map.of(filterField.getId(), filterField)));
    }

    private static String predicate(DatasetFieldEntity field, String value) throws Exception {
        return new ObjectMapper()
                .writeValueAsString(
                        new FilterExpression(
                                null,
                                List.of(),
                                field.getId(),
                                FilterExpression.FilterOperator.EQ,
                                List.of(JsonNodeFactory.instance.textNode(value))));
    }

    private static QueryPlan.ResolvedPath path(
            DatasetEntity source, DatasetEntity target, String sourceColumn, String targetColumn) {
        DatasetFieldEntity sourceField = field(source, sourceColumn);
        DatasetFieldEntity targetField = field(target, targetColumn);
        SemanticCatalogReader.RelationshipDefinition relationship =
                new SemanticCatalogReader.RelationshipDefinition(
                        UUID.randomUUID(),
                        source.getId(),
                        target.getId(),
                        DatasetRelationshipGraph.Cardinality.MANY_TO_ONE,
                        List.of(new SemanticCatalogReader.FieldPair(sourceField, targetField)),
                        source,
                        target);
        return new QueryPlan.ResolvedPath(target.getId(), List.of(relationship));
    }

    private static QueryPlan.DimensionProjection dimension(
            DatasetEntity dataset, String name, String column) {
        DatasetFieldEntity field = field(dataset, column);
        DimensionEntity dimension = new DimensionEntity();
        dimension.setId(UUID.randomUUID());
        dimension.setDatasetId(dataset.getId());
        dimension.setDatasetFieldId(field.getId());
        dimension.setName(name);
        dimension.setDimensionType("STANDARD");
        return new QueryPlan.DimensionProjection(
                new SemanticCatalogReader.DimensionDefinition(dimension, dataset, field, Set.of()),
                null);
    }

    private static QueryPlan.MetricProjection metric(
            DatasetEntity dataset, String name, String column, String aggregation) {
        DatasetFieldEntity field = column == null ? null : field(dataset, column);
        MetricEntity metric = new MetricEntity();
        metric.setId(UUID.randomUUID());
        metric.setDatasetId(dataset.getId());
        metric.setDatasetFieldId(field == null ? null : field.getId());
        metric.setName(name);
        metric.setAggregation(aggregation);
        return new QueryPlan.MetricProjection(
                new SemanticCatalogReader.MetricDefinition(metric, dataset, field));
    }

    private static DatasetEntity dataset(String schema, String object) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(UUID.randomUUID());
        dataset.setSchemaName(schema);
        dataset.setObjectName(object);
        return dataset;
    }

    private static DatasetFieldEntity field(DatasetEntity dataset, String name) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(UUID.randomUUID());
        field.setDatasetId(dataset.getId());
        field.setPhysicalName(name);
        return field;
    }
}
