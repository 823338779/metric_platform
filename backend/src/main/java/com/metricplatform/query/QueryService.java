package com.metricplatform.query;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.query.compiler.CompiledQuery;
import com.metricplatform.query.compiler.SemanticSqlCompiler;
import com.metricplatform.query.planner.QueryPlan;
import com.metricplatform.query.planner.QueryPlanner;
import com.metricplatform.source.JdbcConnectionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_ROWS = 1000;

    private final QueryPlanner planner;
    private final SemanticSqlCompiler compiler;
    private final DataSourceMapper sources;
    private final JdbcConnectionService connections;

    public QueryService(
            QueryPlanner planner,
            SemanticSqlCompiler compiler,
            DataSourceMapper sources,
            JdbcConnectionService connections) {
        this.planner = planner;
        this.compiler = compiler;
        this.sources = sources;
        this.connections = connections;
    }

    public QueryDtos.ValidationResponse validate(QueryDtos.QueryRequest request) {
        QueryPlan plan = planner.plan(request);
        activeSource(plan);
        CompiledQuery compiled = compiler.compile(plan);
        List<java.util.UUID> relationshipIds =
                plan.aggregates().stream()
                        .flatMap(aggregate -> aggregate.paths().stream())
                        .flatMap(path -> path.relationships().stream())
                        .map(
                                com.metricplatform.query.SemanticCatalogReader
                                                .RelationshipDefinition
                                        ::id)
                        .distinct()
                        .toList();
        return new QueryDtos.ValidationResponse(
                true,
                plan.metrics().stream()
                        .map(
                                metric ->
                                        new QueryDtos.SemanticSelection(
                                                metric.definition().metric().getId(),
                                                metric.definition().dataset().getId(),
                                                metric.definition().metric().getName()))
                        .toList(),
                plan.dimensions().stream()
                        .map(
                                dimension ->
                                        new QueryDtos.SemanticSelection(
                                                dimension.definition().dimension().getId(),
                                                dimension.definition().dataset().getId(),
                                                dimension.definition().dimension().getName()))
                        .toList(),
                plan.aggregates().stream()
                        .map(
                                aggregate ->
                                        new QueryDtos.AggregatePlanSummary(
                                                aggregate.datasetId(),
                                                aggregate.metrics().stream()
                                                        .map(
                                                                metric ->
                                                                        metric.definition()
                                                                                .metric()
                                                                                .getId())
                                                        .toList(),
                                                aggregate.dimensions().stream()
                                                        .map(
                                                                dimension ->
                                                                        dimension
                                                                                .definition()
                                                                                .dimension()
                                                                                .getId())
                                                        .toList(),
                                                aggregate.paths().stream()
                                                        .flatMap(
                                                                path ->
                                                                        path
                                                                                .relationships()
                                                                                .stream())
                                                        .map(
                                                                com.metricplatform.query
                                                                                .SemanticCatalogReader
                                                                                .RelationshipDefinition
                                                                        ::id)
                                                        .distinct()
                                                        .toList()))
                        .toList(),
                relationshipIds,
                compiled.parameters().size());
    }

    public QueryDtos.QueryResponse execute(QueryDtos.QueryRequest request) throws SQLException {
        QueryPlan plan = planner.plan(request);
        CompiledQuery query = compiler.compile(plan);
        DataSourceEntity source = activeSource(plan);
        try (Connection connection = connections.openReadOnly(source);
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(MAX_ROWS);
            for (int index = 0; index < query.parameters().size(); index++) {
                CompiledQuery.SqlParameter parameter = query.parameters().get(index);
                statement.setObject(index + 1, parameter.value(), parameter.jdbcType());
            }
            try (ResultSet result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                List<QueryDtos.ResultColumn> columns = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.add(
                            new QueryDtos.ResultColumn(
                                    metadata.getColumnLabel(index),
                                    metadata.getColumnTypeName(index)));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++)
                        row.add(result.getObject(index));
                    rows.add(row);
                }
                return new QueryDtos.QueryResponse(
                        plan.aggregates().stream()
                                .map(QueryPlan.DatasetAggregatePlan::datasetId)
                                .toList(),
                        columns,
                        rows,
                        plan.metrics().stream()
                                .map(value -> (Object) value.definition().metric())
                                .toList(),
                        plan.dimensions().stream()
                                .map(value -> (Object) value.definition().dimension())
                                .toList());
            }
        }
    }

    private DataSourceEntity activeSource(QueryPlan plan) {
        return java.util.Optional.ofNullable(
                        sources.selectById(
                                plan.metrics().getFirst().definition().dataset().getDataSourceId()))
                .filter(
                        value ->
                                "ACTIVE".equals(value.getStatus())
                                        || "ENABLED".equals(value.getStatus()))
                .orElseThrow(() -> new NoSuchElementException("active data source not found"));
    }
}
