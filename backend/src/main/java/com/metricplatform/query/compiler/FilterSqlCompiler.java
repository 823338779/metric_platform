package com.metricplatform.query.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.query.IdentifierQuoter;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FilterSqlCompiler {
    public record CompiledPredicate(String sql, List<CompiledQuery.SqlParameter> parameters) {}

    public CompiledPredicate compile(
            FilterExpression expression, Map<UUID, DatasetFieldEntity> fields, String tableAlias) {
        if (expression == null) return new CompiledPredicate("", List.of());
        List<CompiledQuery.SqlParameter> parameters = new ArrayList<>();
        String sql = compileNode(expression, fields, tableAlias, parameters);
        return new CompiledPredicate(sql, List.copyOf(parameters));
    }

    private String compileNode(
            FilterExpression expression,
            Map<UUID, DatasetFieldEntity> fields,
            String alias,
            List<CompiledQuery.SqlParameter> parameters) {
        if (expression.logicalOperator() != null) {
            if (expression.children().isEmpty())
                throw new IllegalArgumentException("empty filter group");
            return "("
                    + String.join(
                            " " + expression.logicalOperator().name() + " ",
                            expression.children().stream()
                                    .map(child -> compileNode(child, fields, alias, parameters))
                                    .toList())
                    + ")";
        }
        DatasetFieldEntity field = fields.get(expression.fieldId());
        if (field == null) throw new IllegalArgumentException("filter references an unknown field");
        String column =
                IdentifierQuoter.quote(alias)
                        + "."
                        + IdentifierQuoter.quote(field.getPhysicalName());
        List<JsonNode> values = expression.values();
        return switch (expression.operator()) {
            case EQ -> binary(column, "=", values, parameters);
            case NE -> binary(column, "<>", values, parameters);
            case GT -> binary(column, ">", values, parameters);
            case GTE -> binary(column, ">=", values, parameters);
            case LT -> binary(column, "<", values, parameters);
            case LTE -> binary(column, "<=", values, parameters);
            case BETWEEN -> {
                require(values, 2);
                add(values.get(0), parameters);
                add(values.get(1), parameters);
                yield column + " BETWEEN ? AND ?";
            }
            case IN -> {
                if (values.isEmpty()) throw new IllegalArgumentException("IN requires values");
                values.forEach(value -> add(value, parameters));
                yield column
                        + " IN ("
                        + String.join(", ", java.util.Collections.nCopies(values.size(), "?"))
                        + ")";
            }
            case CONTAINS -> {
                require(values, 1);
                JsonNode value = values.getFirst();
                parameters.add(
                        new CompiledQuery.SqlParameter(Types.VARCHAR, "%" + value.asText() + "%"));
                yield column + " LIKE ?";
            }
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
        };
    }

    private static String binary(
            String column,
            String operator,
            List<JsonNode> values,
            List<CompiledQuery.SqlParameter> parameters) {
        require(values, 1);
        add(values.getFirst(), parameters);
        return column + " " + operator + " ?";
    }

    private static void require(List<JsonNode> values, int size) {
        if (values.size() != size) throw new IllegalArgumentException("invalid filter value count");
    }

    private static void add(JsonNode node, List<CompiledQuery.SqlParameter> parameters) {
        if (node == null || node.isNull())
            parameters.add(new CompiledQuery.SqlParameter(Types.NULL, null));
        else if (node.isIntegralNumber())
            parameters.add(new CompiledQuery.SqlParameter(Types.BIGINT, node.longValue()));
        else if (node.isFloatingPointNumber())
            parameters.add(new CompiledQuery.SqlParameter(Types.DECIMAL, node.decimalValue()));
        else if (node.isBoolean())
            parameters.add(new CompiledQuery.SqlParameter(Types.BOOLEAN, node.booleanValue()));
        else parameters.add(new CompiledQuery.SqlParameter(Types.VARCHAR, node.asText()));
    }
}
