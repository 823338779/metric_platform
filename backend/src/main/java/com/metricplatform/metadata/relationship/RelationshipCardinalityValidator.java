package com.metricplatform.metadata.relationship;

import static com.metricplatform.metadata.relationship.RelationshipDtos.Cardinality.ONE_TO_ONE;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.source.JdbcConnectionService;
import com.metricplatform.source.JdbcDialectRegistry;
import com.metricplatform.source.StarRocksDataSourceFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RelationshipCardinalityValidator {
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private final DataSourceMapper sources;
    private final ConnectionOpener connections;
    private final JdbcDialectRegistry dialects;

    @Autowired
    public RelationshipCardinalityValidator(
            DataSourceMapper sources,
            JdbcConnectionService connections,
            JdbcDialectRegistry dialects) {
        this.sources = sources;
        this.connections = connections::openReadOnly;
        this.dialects = dialects;
    }

    RelationshipCardinalityValidator(
            DataSourceMapper sources, StarRocksDataSourceFactory connections) {
        this.sources = sources;
        this.connections = connections::openReadOnly;
        this.dialects = new JdbcDialectRegistry();
    }

    public record Endpoint(DatasetEntity dataset, List<DatasetFieldEntity> keyFields) {}

    public void validate(
            Endpoint source, Endpoint target, RelationshipDtos.Cardinality cardinality) {
        DataSourceEntity dataSource =
                java.util.Optional.ofNullable(
                                sources.selectById(source.dataset().getDataSourceId()))
                        .orElseThrow(() -> new NoSuchElementException("data source not found"));
        try (Connection connection = connections.open(dataSource)) {
            if (cardinality == ONE_TO_ONE && hasDuplicates(connection, dataSource, source))
                throw new IllegalArgumentException(
                        "source key is not unique for ONE_TO_ONE relationship");
            if (hasDuplicates(connection, dataSource, target))
                throw new IllegalArgumentException(
                        "target key is not unique for " + cardinality + " relationship");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "relationship cardinality validation failed", exception);
        }
    }

    private boolean hasDuplicates(Connection connection, DataSourceEntity source, Endpoint endpoint)
            throws SQLException {
        String columns =
                endpoint.keyFields().stream()
                        .map(DatasetFieldEntity::getPhysicalName)
                        .map(name -> quote(connection, name))
                        .collect(Collectors.joining(", "));
        String nonNull =
                endpoint.keyFields().stream()
                        .map(DatasetFieldEntity::getPhysicalName)
                        .map(name -> quote(connection, name))
                        .map(name -> name + " IS NOT NULL")
                        .collect(Collectors.joining(" AND "));
        boolean postgres = "POSTGRESQL".equals(source.getDatabaseType());
        String catalog = postgres ? source.getDefaultCatalog() : endpoint.dataset().getSchemaName();
        String table =
                dialects.required(
                                source.getDatabaseType() == null
                                        ? "STARROCKS"
                                        : source.getDatabaseType())
                        .qualify(
                                connection.getMetaData(),
                                catalog,
                                endpoint.dataset().getSchemaName(),
                                endpoint.dataset().getObjectName());
        String sql =
                "SELECT 1 FROM "
                        + table
                        + " WHERE "
                        + nonNull
                        + " GROUP BY "
                        + columns
                        + " HAVING COUNT(*) > 1 LIMIT 1";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery(sql)) {
                return result.next();
            }
        }
    }

    private static String quote(Connection connection, String identifier) {
        if (identifier == null || identifier.isBlank())
            throw new IllegalArgumentException("physical identifier is required");
        try {
            String marker = connection.getMetaData().getIdentifierQuoteString();
            marker = marker == null ? "" : marker.trim();
            return marker.isEmpty()
                    ? identifier
                    : marker + identifier.replace(marker, marker + marker) + marker;
        } catch (SQLException exception) {
            throw new IllegalStateException("identifier quoting failed", exception);
        }
    }

    @FunctionalInterface
    private interface ConnectionOpener {
        Connection open(DataSourceEntity source) throws SQLException;
    }
}
