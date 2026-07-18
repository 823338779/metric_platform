package com.metricplatform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MultiFactSemanticQueryTest {
    @Test
    void localStarRocksFixtureIsReadOnlyBoundedAndSeeded() throws Exception {
        String url = System.getenv("STARROCKS_JDBC_URL");
        String user = System.getenv("STARROCKS_USERNAME");
        String password = System.getenv("STARROCKS_PASSWORD");
        String expected = System.getenv("STARROCKS_EXPECTED_GROSS_SALES");
        Assumptions.assumeTrue(
                url != null && user != null && password != null && expected != null,
                "local StarRocks acceptance fixture is not configured");

        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            assertThat(connection.isReadOnly()).isTrue();
            try (var statement = connection.createStatement()) {
                statement.setMaxRows(1000);
                statement.setQueryTimeout(10);
                try (var result =
                        statement.executeQuery(
                                "SELECT SUM(gross_amount) FROM ecommerce_dw.fact_orders")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBigDecimal(1))
                            .isEqualByComparingTo(new BigDecimal(expected));
                    assertThat(statement.getMaxRows()).isEqualTo(1000);
                }
            }
        }
    }
}
