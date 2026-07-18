package com.metricplatform.source;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

public interface JdbcDialect {
    String databaseType();

    String driverClass();

    boolean acceptsUrl(String jdbcUrl);

    String effectiveCatalog(String catalog, String schema);

    String effectiveSchema(String catalog, String schema);

    String qualify(DatabaseMetaData metadata, String catalog, String schema, String object)
            throws SQLException;
}
