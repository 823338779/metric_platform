package com.metricplatform.source;

import com.metricplatform.persistence.entity.DataSourceEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
public class StarRocksDataSourceFactory {
    public Connection openReadOnly(DataSourceEntity source) throws SQLException {
        if (source.getJdbcUrl() == null || !source.getJdbcUrl().startsWith("jdbc:mysql:"))
            throw new IllegalArgumentException("StarRocks requires MySQL JDBC URL");
        Connection c =
                DriverManager.getConnection(
                        source.getJdbcUrl(),
                        source.getUsername(),
                        source.getPassword());
        c.setReadOnly(true);
        c.setNetworkTimeout(Runnable::run, 5000);
        return c;
    }
}
