package com.metricplatform.query.compiler;

import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.BETWEEN;
import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.EQ;
import static com.metricplatform.metadata.dataset.FilterExpression.LogicalOperator.AND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilterSqlCompilerTest {
    @Test
    void preservesLegacySqlTimestampLiterals() {
        var field = field("occurred_at");
        field.setStarrocksType("DATETIME");
        var expression =
                new FilterExpression(
                        null,
                        List.of(),
                        field.getId(),
                        EQ,
                        List.of(JsonNodeFactory.instance.textNode("2026-09-16 12:30:00")));
        var compiled =
                new FilterSqlCompiler().compile(expression, Map.of(field.getId(), field), "d");
        assertThat(compiled.parameters())
                .containsExactly(
                        new CompiledQuery.SqlParameter(
                                java.sql.Types.VARCHAR, "2026-09-16 12:30:00"));
    }

    @Test
    void bindsTemporalFieldsAndLargeIntegersWithoutLosingMeaning() {
        for (var sample :
                new Object[][] {
                    {
                        "DATE",
                        "2026-09-16",
                        java.sql.Types.DATE,
                        java.time.LocalDate.parse("2026-09-16")
                    },
                    {
                        "TIMESTAMP",
                        "2026-09-16T12:30:00",
                        java.sql.Types.TIMESTAMP,
                        java.time.LocalDateTime.parse("2026-09-16T12:30:00")
                    },
                    {
                        "TIMESTAMP WITH TIME ZONE",
                        "2026-09-16T12:30:00+08:00",
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE,
                        java.time.OffsetDateTime.parse("2026-09-16T12:30:00+08:00")
                    }
                }) {
            var field = field("occurred_at");
            field.setStarrocksType((String) sample[0]);
            var expression =
                    new FilterExpression(
                            null,
                            List.of(),
                            field.getId(),
                            EQ,
                            List.of(JsonNodeFactory.instance.textNode((String) sample[1])));
            var compiled =
                    new FilterSqlCompiler().compile(expression, Map.of(field.getId(), field), "d");
            assertThat(compiled.parameters())
                    .containsExactly(new CompiledQuery.SqlParameter((int) sample[2], sample[3]));
        }
        for (var number : List.of("9223372036854775808", "-9223372036854775809")) {
            var field = field("amount");
            field.setStarrocksType("NUMERIC");
            var expression =
                    new FilterExpression(
                            null,
                            List.of(),
                            field.getId(),
                            EQ,
                            List.of(
                                    JsonNodeFactory.instance.numberNode(
                                            new java.math.BigInteger(number))));
            var compiled =
                    new FilterSqlCompiler().compile(expression, Map.of(field.getId(), field), "d");
            assertThat(compiled.parameters())
                    .containsExactly(
                            new CompiledQuery.SqlParameter(
                                    java.sql.Types.DECIMAL, new java.math.BigDecimal(number)));
        }
    }

    @Test
    void compilesNotInWithBoundValuesAndRejectsEmptyValues() {
        DatasetFieldEntity status = field("status");
        var operator = FilterExpression.FilterOperator.valueOf("NOT_IN");
        FilterExpression expression =
                new FilterExpression(
                        null,
                        List.of(),
                        status.getId(),
                        operator,
                        List.of(
                                JsonNodeFactory.instance.textNode("DRAFT"),
                                JsonNodeFactory.instance.textNode("CANCELLED")));
        var result =
                new FilterSqlCompiler().compile(expression, Map.of(status.getId(), status), "d0");
        assertThat(result.sql()).isEqualTo("`d0`.`status` NOT IN (?, ?)");
        assertThat(result.parameters())
                .extracting(CompiledQuery.SqlParameter::value)
                .containsExactly("DRAFT", "CANCELLED");
        assertThatThrownBy(
                        () ->
                                new FilterSqlCompiler()
                                        .compile(
                                                new FilterExpression(
                                                        null,
                                                        List.of(),
                                                        status.getId(),
                                                        operator,
                                                        List.of()),
                                                Map.of(status.getId(), status),
                                                "d0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
