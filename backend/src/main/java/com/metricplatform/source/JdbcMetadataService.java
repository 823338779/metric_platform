package com.metricplatform.source;

import static com.metricplatform.source.DataSourceDtos.*;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JdbcMetadataService {
    private static final Set<Integer> PREVIEW_LIMITS = Set.of(20, 50, 100);
    private final DataSourceMapper sources;
    private final JdbcConnectionService connections;
    private final JdbcDialectRegistry dialects;

    public JdbcMetadataService(
            DataSourceMapper sources,
            JdbcConnectionService connections,
            JdbcDialectRegistry dialects) {
        this.sources = sources;
        this.connections = connections;
        this.dialects = dialects;
    }

    public List<CatalogResponse> catalogs(UUID sourceId) throws SQLException {
        DataSourceEntity source = active(sourceId);
        try (Connection connection = connections.openReadOnly(source)) {
            List<CatalogResponse> values = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getCatalogs()) {
                while (rows.next()) addDistinctCatalog(values, rows.getString("TABLE_CAT"));
            }
            addDistinctCatalog(
                    values,
                    first(
                            source.getDefaultCatalog(),
                            first(source.getDatabaseName(), connection.getCatalog())));
            return values.stream()
                    .sorted(
                            Comparator.comparing(
                                    CatalogResponse::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public List<SchemaResponse> schemas(UUID sourceId, String catalogName) throws SQLException {
        DataSourceEntity source = active(sourceId);
        JdbcDialect dialect = dialect(source);
        if (!"POSTGRESQL".equals(dialect.databaseType())) {
            String catalog =
                    required(
                            first(
                                    catalogName,
                                    first(source.getDefaultCatalog(), source.getDatabaseName())),
                            "catalog");
            return List.of(new SchemaResponse(catalog, catalog));
        }
        try (Connection connection = connections.openReadOnly(source)) {
            List<SchemaResponse> values = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getSchemas(catalogName, "%")) {
                while (rows.next())
                    values.add(
                            new SchemaResponse(
                                    rows.getString("TABLE_CATALOG"),
                                    rows.getString("TABLE_SCHEM")));
            }
            return values.stream()
                    .sorted(
                            Comparator.comparing(
                                    SchemaResponse::schemaName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public List<SourceObjectResponse> objects(UUID sourceId, String catalogName, String schemaName)
            throws SQLException {
        DataSourceEntity source = active(sourceId);
        JdbcDialect dialect = dialect(source);
        String catalog =
                dialect.effectiveCatalog(
                        first(catalogName, source.getDefaultCatalog()), schemaName);
        String schema =
                dialect.effectiveSchema(catalogName, first(schemaName, source.getDefaultSchema()));
        try (Connection connection = connections.openReadOnly(source)) {
            List<SourceObjectResponse> values = new ArrayList<>();
            try (ResultSet rows =
                    connection
                            .getMetaData()
                            .getTables(catalog, schema, "%", new String[] {"TABLE", "VIEW"})) {
                while (rows.next())
                    values.add(
                            new SourceObjectResponse(
                                    first(rows.getString("TABLE_CAT"), catalog),
                                            first(rows.getString("TABLE_SCHEM"), schemaName),
                                    rows.getString("TABLE_NAME"),
                                            normalizeType(rows.getString("TABLE_TYPE"))));
            }
            return values.stream()
                    .sorted(
                            Comparator.comparing(
                                    SourceObjectResponse::objectName,
                                    String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public List<SourceFieldResponse> fields(
            UUID sourceId, String catalogName, String schemaName, String objectName)
            throws SQLException {
        DataSourceEntity source = active(sourceId);
        requireObject(sourceId, catalogName, schemaName, objectName);
        JdbcDialect dialect = dialect(source);
        String catalog =
                dialect.effectiveCatalog(
                        first(catalogName, source.getDefaultCatalog()), schemaName);
        String schema =
                dialect.effectiveSchema(catalogName, first(schemaName, source.getDefaultSchema()));
        try (Connection connection = connections.openReadOnly(source)) {
            List<SourceFieldResponse> values = new ArrayList<>();
            try (ResultSet rows =
                    connection.getMetaData().getColumns(catalog, schema, objectName, "%")) {
                while (rows.next())
                    values.add(
                            new SourceFieldResponse(
                                    rows.getString("COLUMN_NAME"),
                                    rows.getString("TYPE_NAME"),
                                    rows.getInt("DATA_TYPE"),
                                    rows.getInt("ORDINAL_POSITION"),
                                    rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
            return values.stream()
                    .sorted(Comparator.comparingInt(SourceFieldResponse::ordinal))
                    .toList();
        }
    }

    public PreviewResponse preview(
            UUID sourceId, String catalogName, String schemaName, String objectName, int limit)
            throws SQLException {
        if (!PREVIEW_LIMITS.contains(limit))
            throw new IllegalArgumentException("preview limit must be 20, 50, or 100");
        DataSourceEntity source = active(sourceId);
        requireObject(sourceId, catalogName, schemaName, objectName);
        try (Connection connection = connections.openReadOnly(source)) {
            String table =
                    dialect(source)
                            .qualify(
                                    connection.getMetaData(),
                                    first(catalogName, source.getDefaultCatalog()),
                                    first(schemaName, source.getDefaultSchema()),
                                    objectName);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.setMaxRows(limit);
                try (ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData metadata = result.getMetaData();
                    List<PreviewColumn> columns = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++)
                        columns.add(
                                new PreviewColumn(
                                        metadata.getColumnLabel(index),
                                        metadata.getColumnTypeName(index),
                                        metadata.getColumnType(index)));
                    List<List<Object>> rows = new ArrayList<>();
                    while (result.next()) {
                        List<Object> row = new ArrayList<>();
                        for (int index = 1; index <= metadata.getColumnCount(); index++)
                            row.add(previewValue(result.getObject(index)));
                        rows.add(row);
                    }
                    return new PreviewResponse(List.copyOf(columns), List.copyOf(rows), limit);
                }
            }
        }
    }

    public SourceObjectResponse requireObject(
            UUID sourceId, String catalogName, String schemaName, String objectName)
            throws SQLException {
        if (objectName == null || objectName.isBlank())
            throw new IllegalArgumentException("object name is required");
        return objects(sourceId, catalogName, schemaName).stream()
                .filter(object -> object.objectName().equalsIgnoreCase(objectName))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("source object not found"));
    }

    private DataSourceEntity active(UUID id) {
        DataSourceEntity source =
                java.util.Optional.ofNullable(sources.selectById(id))
                        .orElseThrow(() -> new NoSuchElementException("data source not found"));
        if (!"ACTIVE".equals(source.getStatus()) && !"ENABLED".equals(source.getStatus()))
            throw new IllegalStateException("data source is not active");
        return source;
    }

    private JdbcDialect dialect(DataSourceEntity source) {
        return dialects.required(first(source.getDatabaseType(), "STARROCKS"));
    }

    private static void addDistinctCatalog(List<CatalogResponse> values, String name) {
        if (name != null
                && !name.isBlank()
                && values.stream().noneMatch(value -> value.name().equalsIgnoreCase(name)))
            values.add(new CatalogResponse(name));
    }

    private static String normalizeType(String type) {
        return type != null && type.toUpperCase().contains("VIEW") ? "VIEW" : "TABLE";
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Object previewValue(Object value) throws SQLException {
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Blob blob) return "[BINARY " + blob.length() + " bytes]";
        if (value instanceof Clob clob)
            return clob.getSubString(1, (int) Math.min(clob.length(), 2000));
        return value;
    }
}
