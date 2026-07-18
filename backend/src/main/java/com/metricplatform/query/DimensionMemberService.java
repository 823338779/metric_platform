package com.metricplatform.query;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import com.metricplatform.persistence.service.DatasetFieldPersistenceService;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.source.ExternalSqlLogger;
import com.metricplatform.source.JdbcConnectionService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DimensionMemberService {
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DimensionEntityPersistenceService dimensions;
    private final DatasetFieldPersistenceService fields;
    private final DatasetEntityPersistenceService datasets;
    private final DataSourcePersistenceService sources;
    private final JdbcConnectionService connections;

    public DimensionMemberService(
            DimensionEntityPersistenceService dimensions,
            DatasetFieldPersistenceService fields,
            DatasetEntityPersistenceService datasets,
            DataSourcePersistenceService sources,
            JdbcConnectionService connections) {
        this.dimensions = dimensions;
        this.fields = fields;
        this.datasets = datasets;
        this.sources = sources;
        this.connections = connections;
    }

    public QueryDtos.DimensionMemberSearchResponse search(
            UUID dimensionId, QueryDtos.DimensionMemberSearchRequest request) throws SQLException {
        DimensionEntity dimension = required(dimensions.getById(dimensionId), "dimension");
        rejectRestricted(dimension);
        DatasetFieldEntity field = required(fields.getById(dimension.getDatasetFieldId()), "field");
        rejectRestricted(field);
        if (!field.isSelected() || !field.isDimensionEnabled()) {
            throw new IllegalArgumentException("dimension field is unavailable for member search");
        }
        DatasetEntity dataset = required(datasets.getById(dimension.getDatasetId()), "dataset");
        DataSourceEntity source = required(sources.getById(dataset.getDataSourceId()), "data source");
        QueryParts query = query(dataset, field, request, source.getDatabaseType());
        int fetchLimit = request.topK() + 1;
        List<QueryDtos.DimensionMember> members = new ArrayList<>();
        try (Connection connection = connections.openReadOnly(source);
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(fetchLimit);
            statement.setString(1, query.parameter());
            ExternalSqlLogger.log(
                    connection,
                    "dimension_member_search",
                    source.getId(),
                    source.getDatabaseName(),
                    query.sql(),
                    List.of(new ExternalSqlLogger.Parameter(Types.VARCHAR, query.parameter())));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next() && members.size() < fetchLimit) {
                    Object raw = result.getObject(1);
                    if (raw != null) {
                        String value = String.valueOf(raw);
                        members.add(new QueryDtos.DimensionMember(value, value));
                    }
                }
            }
        }
        boolean truncated = members.size() > request.topK();
        if (truncated) members = new ArrayList<>(members.subList(0, request.topK()));
        return new QueryDtos.DimensionMemberSearchResponse(dimensionId, List.copyOf(members), truncated);
    }

    private static QueryParts query(
            DatasetEntity dataset,
            DatasetFieldEntity field,
            QueryDtos.DimensionMemberSearchRequest request,
            String databaseType) {
        String column = IdentifierQuoter.quote(field.getPhysicalName(), databaseType);
        String table = (dataset.getSchemaName() == null || dataset.getSchemaName().isBlank()
                        ? ""
                        : IdentifierQuoter.quote(dataset.getSchemaName(), databaseType) + ".")
                + IdentifierQuoter.quote(dataset.getObjectName(), databaseType);
        QueryDtos.MemberMatchMode mode = request.mode() == QueryDtos.MemberMatchMode.AUTO
                ? QueryDtos.MemberMatchMode.PREFIX
                : request.mode();
        String operator = mode == QueryDtos.MemberMatchMode.EXACT ? " = ?" : " LIKE ?";
        String parameter = switch (mode) {
            case EXACT -> request.query();
            case PREFIX, AUTO -> request.query() + "%";
            case CONTAINS -> "%" + request.query() + "%";
        };
        String castType = "POSTGRESQL".equalsIgnoreCase(databaseType) ? "VARCHAR" : "CHAR";
        String sql = "SELECT DISTINCT " + column + " AS member_value FROM " + table
                + " WHERE CAST(" + column + " AS " + castType + ")" + operator
                + " ORDER BY member_value LIMIT " + (request.topK() + 1);
        return new QueryParts(sql, parameter);
    }

    private static void rejectRestricted(DimensionEntity dimension) {
        rejectRestricted(dimension.getSensitivityLevel());
    }

    private static void rejectRestricted(DatasetFieldEntity field) {
        rejectRestricted(field.getSensitivityLevel());
    }

    private static void rejectRestricted(String level) {
        String normalized = level == null ? "INTERNAL" : level.toUpperCase(Locale.ROOT);
        if (SetHolder.RESTRICTED.contains(normalized)) {
            throw new SecurityException("dimension is restricted from member search");
        }
    }

    private static <T> T required(T value, String type) {
        if (value == null) throw new NoSuchElementException(type + " not found");
        return value;
    }

    private record QueryParts(String sql, String parameter) {}

    private static final class SetHolder {
        private static final java.util.Set<String> RESTRICTED =
                java.util.Set.of("RESTRICTED", "CONFIDENTIAL", "SECRET", "PII");
    }
}
