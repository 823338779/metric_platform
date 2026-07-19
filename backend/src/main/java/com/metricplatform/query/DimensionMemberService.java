package com.metricplatform.query;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetFieldPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.source.ExternalSqlLogger;
import com.metricplatform.source.JdbcConnectionService;

import org.springframework.stereotype.Service;

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

@Service
public class DimensionMemberService {
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DimensionEntityPersistenceService dimensions;
    private final DatasetFieldPersistenceService fields;
    private final DatasetEntityPersistenceService datasets;
    private final DataSourcePersistenceService sources;
    private final JdbcConnectionService connections;
    private final DimensionMemberCursorCodec cursors;

    public DimensionMemberService(
            DimensionEntityPersistenceService dimensions,
            DatasetFieldPersistenceService fields,
            DatasetEntityPersistenceService datasets,
            DataSourcePersistenceService sources,
            JdbcConnectionService connections,
            DimensionMemberCursorCodec cursors) {
        this.dimensions = dimensions;
        this.fields = fields;
        this.datasets = datasets;
        this.sources = sources;
        this.connections = connections;
        this.cursors = cursors;
    }

    public QueryDtos.DimensionMemberSearchResponse search(
            UUID dimensionId, QueryDtos.DimensionMemberSearchRequest request) throws SQLException {
        QueryDtos.DimensionMemberQueryResponse response =
                query(
                        dimensionId,
                        new QueryDtos.DimensionMemberQueryRequest(
                                request.query(), request.mode(), request.topK(), null));
        return new QueryDtos.DimensionMemberSearchResponse(
                dimensionId, response.items(), response.pageInfo().hasMore());
    }

    public QueryDtos.DimensionMemberQueryResponse query(
            UUID dimensionId, QueryDtos.DimensionMemberQueryRequest request) throws SQLException {
        DimensionEntity dimension = required(dimensions.getById(dimensionId), "dimension");
        rejectRestricted(dimension);
        DatasetFieldEntity field = required(fields.getById(dimension.getDatasetFieldId()), "field");
        rejectRestricted(field);
        if (!field.isSelected() || !field.isDimensionEnabled()) {
            throw new IllegalArgumentException("dimension field is unavailable for member search");
        }
        DatasetEntity dataset = required(datasets.getById(dimension.getDatasetId()), "dataset");
        DataSourceEntity source =
                required(sources.getById(dataset.getDataSourceId()), "data source");
        String memberVersion =
                "member-%s-%d-%d"
                        .formatted(dimensionId, dimension.getVersion(), field.getVersion());
        String after =
                request.cursor() == null
                        ? null
                        : cursors.decode(
                                        request.cursor(),
                                        dimensionId,
                                        memberVersion,
                                        request.text(),
                                        request.mode())
                                .lastValue();
        QueryParts query = query(dataset, field, request, source.getDatabaseType(), after);
        int fetchLimit = request.limit() + 1;
        List<QueryDtos.DimensionMember> members = new ArrayList<>();
        try (Connection connection = connections.openReadOnly(source);
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(fetchLimit);
            for (int index = 0; index < query.parameters().size(); index++) {
                statement.setString(index + 1, query.parameters().get(index));
            }
            ExternalSqlLogger.log(
                    connection,
                    "dimension_member_search",
                    source.getId(),
                    source.getDatabaseName(),
                    query.sql(),
                    query.parameters().stream()
                            .map(value -> new ExternalSqlLogger.Parameter(Types.VARCHAR, value))
                            .toList());
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
        boolean hasMore = members.size() > request.limit();
        if (hasMore) members = new ArrayList<>(members.subList(0, request.limit()));
        String nextCursor = null;
        if (hasMore && !members.isEmpty()) {
            nextCursor =
                    cursors.encode(
                            new DimensionMemberCursorCodec.Cursor(
                                    dimensionId,
                                    memberVersion,
                                    request.text(),
                                    request.mode(),
                                    members.getLast().value()));
        }
        return new QueryDtos.DimensionMemberQueryResponse(
                dimensionId,
                List.copyOf(members),
                new QueryDtos.PageInfo(nextCursor, hasMore, request.limit()),
                memberVersion);
    }

    private static QueryParts query(
            DatasetEntity dataset,
            DatasetFieldEntity field,
            QueryDtos.DimensionMemberQueryRequest request,
            String databaseType,
            String after) {
        String column = IdentifierQuoter.quote(field.getPhysicalName(), databaseType);
        String table =
                (dataset.getSchemaName() == null || dataset.getSchemaName().isBlank()
                                ? ""
                                : IdentifierQuoter.quote(dataset.getSchemaName(), databaseType)
                                        + ".")
                        + IdentifierQuoter.quote(dataset.getObjectName(), databaseType);
        String castType = "POSTGRESQL".equalsIgnoreCase(databaseType) ? "VARCHAR" : "CHAR";
        String comparable = "CAST(" + column + " AS " + castType + ")";
        StringBuilder where = new StringBuilder(" WHERE ").append(column).append(" IS NOT NULL");
        List<String> parameters = new ArrayList<>();
        if (request.text() != null) {
            QueryDtos.MemberMatchMode mode =
                    request.mode() == QueryDtos.MemberMatchMode.AUTO
                            ? QueryDtos.MemberMatchMode.PREFIX
                            : request.mode();
            where.append(
                    mode == QueryDtos.MemberMatchMode.EXACT
                            ? " AND " + comparable + " = ?"
                            : " AND " + comparable + " LIKE ?");
            parameters.add(
                    switch (mode) {
                        case EXACT -> request.text();
                        case PREFIX, AUTO -> request.text() + "%";
                        case CONTAINS -> "%" + request.text() + "%";
                    });
        }
        if (after != null) {
            where.append(" AND ").append(comparable).append(" > ?");
            parameters.add(after);
        }
        String sql =
                "SELECT DISTINCT "
                        + comparable
                        + " AS member_value FROM "
                        + table
                        + where
                        + " ORDER BY member_value LIMIT "
                        + (request.limit() + 1);
        return new QueryParts(sql, List.copyOf(parameters));
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

    private record QueryParts(String sql, List<String> parameters) {}

    private static final class SetHolder {
        private static final java.util.Set<String> RESTRICTED =
                java.util.Set.of("RESTRICTED", "CONFIDENTIAL", "SECRET", "PII");
    }
}
