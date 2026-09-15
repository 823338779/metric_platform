package com.metricplatform.query.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.query.IdentifierQuoter;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FilterSqlCompiler {
    private static final String IN_SQL = " IN (";
    private static final String NOT_IN_SQL = " NOT IN (";
    private static final String DATE = "DATE",
            DATETIME = "DATETIME",
            TIMESTAMP = "TIMESTAMP",
            TIMESTAMPTZ = "TIMESTAMPTZ",
            WITH_ZONE = "TIMESTAMP WITH TIME ZONE",
            WITHOUT_ZONE = "TIMESTAMP WITHOUT TIME ZONE";

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
            case EQ -> binary(column, "=", values, parameters, field);
            case NE -> binary(column, "<>", values, parameters, field);
            case GT -> binary(column, ">", values, parameters, field);
            case GTE -> binary(column, ">=", values, parameters, field);
            case LT -> binary(column, "<", values, parameters, field);
            case LTE -> binary(column, "<=", values, parameters, field);
            case BETWEEN -> {
                require(values, 2);
                add(values.get(0), parameters, field);
                add(values.get(1), parameters, field);
                yield column + " BETWEEN ? AND ?";
            }
            case IN, NOT_IN -> {
                if (values.isEmpty())
                    throw new IllegalArgumentException("IN/NOT_IN requires values");
                values.forEach(value -> add(value, parameters, field));
                yield column
                        + (expression.operator() == FilterExpression.FilterOperator.NOT_IN
                                ? NOT_IN_SQL
                                : IN_SQL)
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
            List<CompiledQuery.SqlParameter> parameters,
            DatasetFieldEntity field) {
        require(values, 1);
        add(values.getFirst(), parameters, field);
        return column + " " + operator + " ?";
    }

    private static void require(List<JsonNode> values, int size) {
        if (values.size() != size) throw new IllegalArgumentException("invalid filter value count");
    }

    private static void add(
            JsonNode node, List<CompiledQuery.SqlParameter> parameters, DatasetFieldEntity field) {
        String type =
                field.getStarrocksType() == null
                        ? ""
                        : field.getStarrocksType()
                                .toUpperCase(java.util.Locale.ROOT)
                                .replaceAll("\\([^)]*\\)", "")
                                .trim();
        // 固定过滤的日期在 JSON 中是字符串；按物理类型绑定，不能以 VARCHAR 比较日期。
        var temporal = node != null && node.isTextual() ? temporal(type, node.textValue()) : null;
        if (node == null || node.isNull())
            parameters.add(new CompiledQuery.SqlParameter(Types.NULL, null));
        else if (temporal != null) parameters.add(temporal);
        else if (node.isIntegralNumber() && node.canConvertToLong())
            parameters.add(new CompiledQuery.SqlParameter(Types.BIGINT, node.longValue()));
        // 大整数不能经过 longValue 截断；DECIMAL 同时保存高精度小数与超长整数。
        else if (node.isNumber())
            parameters.add(new CompiledQuery.SqlParameter(Types.DECIMAL, node.decimalValue()));
        else if (node.isBoolean())
            parameters.add(new CompiledQuery.SqlParameter(Types.BOOLEAN, node.booleanValue()));
        else parameters.add(new CompiledQuery.SqlParameter(Types.VARCHAR, node.asText()));
    }

    private static CompiledQuery.SqlParameter temporal(String type, String value) {
        try {
            return switch (type) {
                case DATE -> new CompiledQuery.SqlParameter(Types.DATE, LocalDate.parse(value));
                case TIMESTAMPTZ, WITH_ZONE ->
                        new CompiledQuery.SqlParameter(
                                Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.parse(value));
                case TIMESTAMP, DATETIME, WITHOUT_ZONE ->
                        new CompiledQuery.SqlParameter(Types.TIMESTAMP, LocalDateTime.parse(value));
                default -> null;
            };
        } catch (java.time.format.DateTimeParseException ignored) {
            // 人工数据集历史上允许数据库自己的日期文本格式，继续按原 VARCHAR 路径处理。
            // Git 导入已严格校验 ISO 与时区类型，固定指标口径不会走此兼容分支。
            return null;
        }
    }
}
