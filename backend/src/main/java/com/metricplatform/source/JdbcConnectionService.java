package com.metricplatform.source;

import com.metricplatform.persistence.entity.DataSourceEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JdbcConnectionService {
    private final JdbcDialectRegistry dialects;

    public Connection openReadOnly(DataSourceEntity source) throws SQLException {
        if (!"ACTIVE".equals(source.getStatus()) && !"ENABLED".equals(source.getStatus())) {
            throw new IllegalStateException("data source is not active");
        }
        return openForTest(source);
    }

    public Connection openForTest(DataSourceEntity source) throws SQLException {
        return openReadOnly(
                new JdbcConnectionSettings(
                        source.getDatabaseType() == null ? "STARROCKS" : source.getDatabaseType(),
                        source.getJdbcUrl(),
                        source.getUsername(),
                        source.getPassword(),
                        source.getDefaultCatalog() == null
                                ? source.getDatabaseName()
                                : source.getDefaultCatalog(),
                        source.getDefaultSchema()));
    }

    public Connection openReadOnly(JdbcConnectionSettings settings) throws SQLException {
        JdbcDialect dialect = dialects.required(settings.databaseType());
        if (!dialect.acceptsUrl(settings.jdbcUrl()))
            throw new IllegalArgumentException("JDBC URL does not match database type");
        try {
            Class.forName(dialect.driverClass());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("configured JDBC driver is unavailable", exception);
        }
        Connection connection =
                DriverManager.getConnection(
                        settings.jdbcUrl(), settings.username(), settings.password());
        try {
            connection.setReadOnly(true);
            connection.setNetworkTimeout(Runnable::run, 5000);
            if (settings.defaultCatalog() != null
                    && !settings.defaultCatalog().isBlank()
                    && !settings.defaultCatalog().equals(connection.getCatalog())) {
                connection.setCatalog(settings.defaultCatalog());
            }
            if (settings.defaultSchema() != null && !settings.defaultSchema().isBlank()) {
                connection.setSchema(settings.defaultSchema());
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            throw exception;
        }
    }
}
