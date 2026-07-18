package com.metricplatform.query.compiler;

import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.BETWEEN;
import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.EQ;
import static com.metricplatform.metadata.dataset.FilterExpression.LogicalOperator.AND;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilterSqlCompilerTest {
    @Test
    void compilesNestedPredicatesWithBoundValues() {
        DatasetFieldEntity status = field("status");
        DatasetFieldEntity amount = field("amount");
        FilterExpression filter =
                new FilterExpression(
                        AND,
                        List.of(
                                new FilterExpression(
                                        null,
                                        List.of(),
                                        status.getId(),
                                        EQ,
                                        List.of(JsonNodeFactory.instance.textNode("PAID"))),
                                new FilterExpression(
                                        null,
                                        List.of(),
                                        amount.getId(),
                                        BETWEEN,
                                        List.of(
                                                JsonNodeFactory.instance.numberNode(10),
                                                JsonNodeFactory.instance.numberNode(100)))),
                        null,
                        null,
                        List.of());

        FilterSqlCompiler.CompiledPredicate result =
                new FilterSqlCompiler()
                        .compile(
                                filter,
                                Map.of(status.getId(), status, amount.getId(), amount),
                                "d0");

        assertThat(result.sql()).isEqualTo("(`d0`.`status` = ? AND `d0`.`amount` BETWEEN ? AND ?)");
        assertThat(result.parameters())
                .extracting(CompiledQuery.SqlParameter::value)
                .containsExactly("PAID", 10L, 100L);
        assertThat(result.sql()).doesNotContain("PAID", "100");
    }

    private static DatasetFieldEntity field(String name) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(UUID.randomUUID());
        field.setPhysicalName(name);
        return field;
    }
}
