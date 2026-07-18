package com.metricplatform.metadata.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record FilterExpression(
        LogicalOperator logicalOperator,
        List<FilterExpression> children,
        UUID fieldId,
        FilterOperator operator,
        List<JsonNode> values) {

    public FilterExpression {
        children = children == null ? List.of() : List.copyOf(children);
        values = values == null ? List.of() : List.copyOf(values);
    }

    public enum LogicalOperator {
        AND,
        OR
    }

    public enum FilterOperator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        BETWEEN,
        IN,
        CONTAINS,
        IS_NULL,
        IS_NOT_NULL
    }
}
