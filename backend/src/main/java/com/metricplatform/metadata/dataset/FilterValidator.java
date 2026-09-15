package com.metricplatform.metadata.dataset;

import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class FilterValidator {
    public void validate(
            UUID datasetId, FilterExpression expression, Map<UUID, DatasetFieldEntity> fields) {
        if (expression == null) {
            return;
        }
        if (expression.logicalOperator() != null) {
            validateGroup(datasetId, expression, fields);
            return;
        }
        validatePredicate(datasetId, expression, fields);
    }

    private void validateGroup(
            UUID datasetId, FilterExpression expression, Map<UUID, DatasetFieldEntity> fields) {
        if (expression.children().isEmpty()) {
            throw new IllegalArgumentException("filter group requires at least one child");
        }
        if (expression.fieldId() != null
                || expression.operator() != null
                || !expression.values().isEmpty()) {
            throw new IllegalArgumentException("filter group cannot contain predicate properties");
        }
        expression.children().forEach(child -> validate(datasetId, child, fields));
    }

    private void validatePredicate(
            UUID datasetId, FilterExpression expression, Map<UUID, DatasetFieldEntity> fields) {
        if (!expression.children().isEmpty()
                || expression.fieldId() == null
                || expression.operator() == null) {
            throw new IllegalArgumentException(
                    "filter predicate requires a field and operator only");
        }
        DatasetFieldEntity field = fields.get(expression.fieldId());
        if (field == null || !datasetId.equals(field.getDatasetId())) {
            throw new IllegalArgumentException("filter field must belong to the same dataset");
        }
        if (!field.isSelected()) {
            throw new IllegalArgumentException("filter field must be selected");
        }

        int valueCount = expression.values().size();
        switch (expression.operator()) {
            case IS_NULL, IS_NOT_NULL ->
                    require(valueCount == 0, "null predicate does not accept values");
            case BETWEEN -> require(valueCount == 2, "BETWEEN requires exactly two values");
            case IN, NOT_IN ->
                    require(valueCount > 0, expression.operator() + " requires at least one value");
            default ->
                    require(valueCount == 1, expression.operator() + " requires exactly one value");
        }

        if (expression.operator() == FilterExpression.FilterOperator.CONTAINS
                && !isString(field.getStarrocksType())) {
            throw new IllegalArgumentException("CONTAINS requires a string field");
        }
    }

    private static boolean isString(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return normalized.contains("CHAR")
                || normalized.contains("STRING")
                || normalized.contains("TEXT");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
