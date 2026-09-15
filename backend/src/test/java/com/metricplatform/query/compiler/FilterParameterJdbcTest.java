package com.metricplatform.query.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证与生产相同的 JDBC setObject 绑定；不声称 PostgreSQL 支持现有反引号查询方言。 */
class FilterParameterJdbcTest {
    @Test
    void databaseComparesTemporalBoundParameters() throws Exception {
        try (var connection =
                DriverManager.getConnection("jdbc:tc:postgresql:16-alpine:///git_filter_binding")) {
            for (var example :
                    new String[][] {
                        {"DATE", "2026-09-16", "SELECT DATE '2026-09-16' = ?"},
                        {
                            "TIMESTAMP",
                            "2026-09-16T12:30:00",
                            "SELECT TIMESTAMP '2026-09-16 12:30:00' = ?"
                        },
                        {
                            "TIMESTAMPTZ",
                            "2026-09-16T12:30:00+08:00",
                            "SELECT TIMESTAMPTZ '2026-09-16 04:30:00Z' = ?"
                        }
                    }) {
                var field = new DatasetFieldEntity();
                field.setId(UUID.randomUUID());
                field.setPhysicalName("occurred_at");
                field.setStarrocksType(example[0]);
                var expression =
                        new FilterExpression(
                                null,
                                List.of(),
                                field.getId(),
                                FilterExpression.FilterOperator.EQ,
                                List.of(JsonNodeFactory.instance.textNode(example[1])));
                var parameter =
                        new FilterSqlCompiler()
                                .compile(expression, Map.of(field.getId(), field), "d")
                                .parameters()
                                .getFirst();
                try (var statement = connection.prepareStatement(example[2])) {
                    statement.setObject(1, parameter.value(), parameter.jdbcType());
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isTrue();
                    }
                }
            }
        }
    }
}
