package com.metricplatform.source;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StarRocksMetadataClient {
    public record SourceObject(String schema, String name, String type) {}

    public record SourceColumn(String name, String type, int ordinal) {}

    public List<SourceObject> tables(Connection connection) throws SQLException {
        List<SourceObject> result = new ArrayList<>();
        try (ResultSet rows =
                connection
                        .getMetaData()
                        .getTables(
                                connection.getCatalog(),
                                null,
                                "%",
                                new String[] {"TABLE", "VIEW"})) {
            while (rows.next()) {
                String schema = rows.getString("TABLE_SCHEM");
                if (schema == null || schema.isBlank()) {
                    schema = rows.getString("TABLE_CAT");
                }
                if (schema == null || schema.isBlank()) {
                    schema = connection.getCatalog();
                }
                result.add(
                        new SourceObject(
                                schema,
                                rows.getString("TABLE_NAME"),
                                rows.getString("TABLE_TYPE")));
            }
        }
        return result;
    }

    public List<SourceColumn> columns(Connection connection, String schema, String table)
            throws SQLException {
        List<SourceColumn> result = new ArrayList<>();
        String catalog = schema == null || schema.isBlank() ? connection.getCatalog() : schema;
        try (ResultSet rows = connection.getMetaData().getColumns(catalog, null, table, "%")) {
            while (rows.next()) {
                result.add(
                        new SourceColumn(
                                rows.getString("COLUMN_NAME"),
                                rows.getString("TYPE_NAME"),
                                rows.getInt("ORDINAL_POSITION")));
            }
        }
        return result;
    }
}
