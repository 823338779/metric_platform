package com.metricplatform.query;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import com.metricplatform.query.compiler.CompiledQuery;
import com.metricplatform.query.compiler.SemanticSqlCompiler;
import com.metricplatform.query.planner.QueryPlan;
import com.metricplatform.query.planner.QueryPlanner;
import com.metricplatform.source.ExternalSqlLogger;
import com.metricplatform.source.JdbcConnectionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryService {
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_ROWS = 1000;

    private final QueryPlanner planner;
    private final SemanticSqlCompiler compiler;
    private final DataSourcePersistenceService sources;
    private final JdbcConnectionService connections;

    public QueryDtos.ValidationResponse validate(QueryDtos.QueryRequest request) {
        try {
            QueryPlan plan = planner.plan(request);
            activeSource(plan);
            CompiledQuery compiled = compiler.compile(plan);
            return validationSuccess(request, plan, compiled);
        } catch (RuntimeException exception) {
            QueryValidationException validation = validationException(exception);
            return new QueryDtos.ValidationResponse(
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    List.of(
                            new QueryDtos.ValidationIssue(
                                    validation.code(),
                                    validation.getMessage(),
                                    validation.objectType(),
                                    validation.objectId(),
                                    validation.candidatePaths())),
                    List.of(),
                    request,
                    null,
                    null);
        }
    }

    private QueryDtos.ValidationResponse validationSuccess(
            QueryDtos.QueryRequest request, QueryPlan plan, CompiledQuery compiled) {
        return new QueryDtos.ValidationResponse(
                true,
                plan.metrics().stream()
                        .map(metric -> new QueryDtos.SemanticSelection(
                                metric.definition().metric().getId(),
                                metric.definition().dataset().getId(),
                                metric.definition().metric().getName()))
                        .toList(),
                plan.dimensions().stream()
                        .map(dimension -> new QueryDtos.SemanticSelection(
                                dimension.definition().dimension().getId(),
                                dimension.definition().dataset().getId(),
                                dimension.definition().dimension().getName()))
                        .toList(),
                plan.aggregates().stream()
                        .map(aggregate -> new QueryDtos.AggregatePlanSummary(
                                aggregate.datasetId(),
                                aggregate.metrics().stream()
                                        .map(metric -> metric.definition().metric().getId())
                                        .toList(),
                                aggregate.dimensions().stream()
                                        .map(dimension -> dimension.definition().dimension().getId())
                                        .toList(),
                                aggregate.paths().stream()
                                        .flatMap(path -> path.relationships().stream())
                                        .map(SemanticCatalogReader.RelationshipDefinition::id)
                                        .distinct()
                                        .toList()))
                        .toList(),
                relationshipIds(plan),
                compiled.parameters().size(),
                List.of(),
                freshnessWarnings(plan),
                request,
                catalogVersion(plan),
                null);
    }

    public QueryDtos.QueryResponse execute(QueryDtos.QueryRequest request) throws SQLException {
        QueryPlan plan;
        try {
            plan = planner.plan(request);
        } catch (RuntimeException exception) {
            throw validationException(exception);
        }
        CompiledQuery query = compiler.compile(plan);
        DataSourceEntity source = activeSource(plan);
        List<ExternalSqlLogger.Parameter> sqlParameters = query.parameters().stream()
                .map(value -> new ExternalSqlLogger.Parameter(value.jdbcType(), value.value()))
                .toList();
        String completeSql = ExternalSqlLogger.renderCompleteSql(query.sql(), sqlParameters);
        try (Connection connection = connections.openReadOnly(source);
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(Math.min(MAX_ROWS + 1, plan.limit() + 1));
            for (int index = 0; index < query.parameters().size(); index++) {
                CompiledQuery.SqlParameter parameter = query.parameters().get(index);
                statement.setObject(index + 1, parameter.value(), parameter.jdbcType());
            }
            ExternalSqlLogger.log(
                    connection,
                    "semantic_query",
                    source.getId(),
                    source.getDatabaseName(),
                    query.sql(),
                    sqlParameters);
            long startedAt = System.nanoTime();
            try (ResultSet result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                List<QueryDtos.ResultColumn> columns = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.add(resultColumn(plan, index, metadata.getColumnLabel(index),
                            metadata.getColumnTypeName(index)));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (result.next() && rows.size() <= plan.limit()) {
                    List<Object> row = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.add(result.getObject(index));
                    }
                    rows.add(row);
                }
                boolean truncated = rows.size() > plan.limit();
                if (truncated) rows = new ArrayList<>(rows.subList(0, plan.limit()));
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                UUID queryId = UUID.randomUUID();
                String catalogVersion = catalogVersion(plan);
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
                                .toList(),
                        queryId,
                        rows.size(),
                        truncated,
                        request.filters(),
                        request.timeRange(),
                        freshnessWarnings(plan),
                        catalogVersion,
                        new QueryDtos.QueryEvidence(
                                queryId,
                                catalogVersion,
                                definitionVersions(plan),
                                relationshipIds(plan)),
                        completeSql,
                        durationMs);
            }
        }
    }

    private static QueryDtos.ResultColumn resultColumn(
            QueryPlan plan, int oneBasedIndex, String name, String physicalType) {
        int index = oneBasedIndex - 1;
        if (index < plan.dimensions().size()) {
            QueryPlan.DimensionProjection dimension = plan.dimensions().get(index);
            var value = dimension.definition().dimension();
            return new QueryDtos.ResultColumn(
                    name,
                    physicalType,
                    QueryDtos.SemanticObjectType.DIMENSION,
                    value.getId(),
                    value.getDisplayName(),
                    value.getValueType(),
                    null,
                    null,
                    dimension.granularity());
        }
        QueryPlan.MetricProjection metric = plan.metrics().get(index - plan.dimensions().size());
        var value = metric.definition().metric();
        return new QueryDtos.ResultColumn(
                name,
                physicalType,
                QueryDtos.SemanticObjectType.METRIC,
                value.getId(),
                value.getDisplayName(),
                physicalType,
                value.getUnit(),
                value.getFormatPattern(),
                null);
    }

    private DataSourceEntity activeSource(QueryPlan plan) {
        return java.util.Optional.ofNullable(sources.getById(
                        plan.metrics().getFirst().definition().dataset().getDataSourceId()))
                .filter(value -> "ACTIVE".equals(value.getStatus())
                        || "ENABLED".equals(value.getStatus()))
                .orElseThrow(() -> new NoSuchElementException("active data source not found"));
    }

    private static List<UUID> relationshipIds(QueryPlan plan) {
        return plan.aggregates().stream()
                .flatMap(aggregate -> aggregate.paths().stream())
                .flatMap(path -> path.relationships().stream())
                .map(SemanticCatalogReader.RelationshipDefinition::id)
                .distinct()
                .toList();
    }

    private static Map<UUID, Long> definitionVersions(QueryPlan plan) {
        Map<UUID, Long> versions = new LinkedHashMap<>();
        plan.metrics().forEach(value -> versions.put(
                value.definition().metric().getId(), value.definition().metric().getVersion()));
        plan.dimensions().forEach(value -> versions.put(
                value.definition().dimension().getId(), value.definition().dimension().getVersion()));
        plan.filters().forEach(value -> versions.put(
                value.definition().dimension().getId(), value.definition().dimension().getVersion()));
        if (plan.timeRange() != null) {
            versions.put(
                    plan.timeRange().definition().dimension().getId(),
                    plan.timeRange().definition().dimension().getVersion());
        }
        return Map.copyOf(versions);
    }

    private static String catalogVersion(QueryPlan plan) {
        long version = definitionVersions(plan).entrySet().stream()
                .mapToLong(entry -> 31L * entry.getKey().hashCode() + entry.getValue())
                .reduce(1L, (left, right) -> 31L * left + right);
        return "catalog-" + Long.toUnsignedString(version);
    }

    private static List<String> freshnessWarnings(QueryPlan plan) {
        return plan.aggregates().stream()
                .map(aggregate -> aggregate.metrics().getFirst().definition().dataset())
                .filter(dataset -> dataset.getQualityStatus() != null
                        && !"PASS".equalsIgnoreCase(dataset.getQualityStatus())
                        && !"UNKNOWN".equalsIgnoreCase(dataset.getQualityStatus()))
                .map(dataset -> "Dataset " + dataset.getName() + " quality status is "
                        + dataset.getQualityStatus())
                .distinct()
                .toList();
    }

    private static QueryValidationException validationException(RuntimeException exception) {
        if (exception instanceof QueryValidationException validation) return validation;
        String message = exception.getMessage() == null
                ? "semantic query is invalid"
                : exception.getMessage();
        String normalized = message.toLowerCase(Locale.ROOT);
        String code = exception instanceof NoSuchElementException
                ? "SEMANTIC_OBJECT_NOT_FOUND"
                : normalized.contains("ambiguous relationship")
                        ? "AMBIGUOUS_RELATIONSHIP_PATH"
                        : normalized.contains("relationship path")
                                ? "RELATIONSHIP_PATH_NOT_FOUND"
                                : normalized.contains("data source")
                                        ? "DATA_SOURCE_UNAVAILABLE"
                                        : "INVALID_QUERY_SPEC";
        return new QueryValidationException(code, message);
    }
}
