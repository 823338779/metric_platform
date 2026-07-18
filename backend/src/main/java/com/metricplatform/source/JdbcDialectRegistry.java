package com.metricplatform.source;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

@Component
public class JdbcDialectRegistry {
    private final Map<String, JdbcDialect> dialects =
            Map.of(
                    "STARROCKS", new CatalogDialect("STARROCKS"),
                    "MYSQL", new CatalogDialect("MYSQL"),
                    "POSTGRESQL", new PostgreSqlDialect());

    public JdbcDialect required(String databaseType) {
        JdbcDialect dialect =
                dialects.get(databaseType == null ? "" : databaseType.toUpperCase(Locale.ROOT));
        if (dialect == null) throw new NoSuchElementException("unsupported database type");
        return dialect;
    }

    private static final class CatalogDialect implements JdbcDialect {
        private final String databaseType;

        private CatalogDialect(String databaseType) {
            this.databaseType = databaseType;
        }

        @Override
        public String databaseType() {
            return databaseType;
        }

        @Override
        public String driverClass() {
            return "com.mysql.cj.jdbc.Driver";
        }

        @Override
        public boolean acceptsUrl(String jdbcUrl) {
            return jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql:");
        }

        @Override
        public String effectiveCatalog(String catalog, String schema) {
            return first(catalog, schema);
        }

        @Override
        public String effectiveSchema(String catalog, String schema) {
            return null;
        }

        @Override
        public String qualify(
                DatabaseMetaData metadata, String catalog, String schema, String object)
                throws SQLException {
            return quote(metadata, first(catalog, schema)) + "." + quote(metadata, object);
        }
    }

    private static final class PostgreSqlDialect implements JdbcDialect {
        @Override
        public String databaseType() {
            return "POSTGRESQL";
        }

        @Override
        public String driverClass() {
            return "org.postgresql.Driver";
        }

        @Override
        public boolean acceptsUrl(String jdbcUrl) {
            return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
        }

        @Override
        public String effectiveCatalog(String catalog, String schema) {
            return blankToNull(catalog);
        }

        @Override
        public String effectiveSchema(String catalog, String schema) {
            return blankToNull(schema);
        }

        @Override
        public String qualify(
                DatabaseMetaData metadata, String catalog, String schema, String object)
                throws SQLException {
            String namespace = blankToNull(schema);
            return namespace == null
                    ? quote(metadata, object)
                    : quote(metadata, namespace) + "." + quote(metadata, object);
        }
    }

    private static String quote(DatabaseMetaData metadata, String identifier) throws SQLException {
        if (identifier == null || identifier.isBlank())
            throw new IllegalArgumentException("physical identifier is required");
        String quote = metadata.getIdentifierQuoteString();
        quote = quote == null ? "" : quote.trim();
        return quote.isEmpty()
                ? identifier
                : quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static String first(String first, String second) {
        String value = blankToNull(first);
        if (value == null) value = blankToNull(second);
        if (value == null) throw new IllegalArgumentException("catalog is required");
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
