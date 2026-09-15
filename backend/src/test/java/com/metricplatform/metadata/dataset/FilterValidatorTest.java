package com.metricplatform.metadata.dataset;

import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.BETWEEN;
import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.CONTAINS;
import static com.metricplatform.metadata.dataset.FilterExpression.FilterOperator.IS_NULL;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilterValidatorTest {
    private static final UUID DATASET_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_DATASET_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID AMOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID STATUS_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

    private final FilterValidator validator = new FilterValidator();
    private final Map<UUID, DatasetFieldEntity> fields =
            Map.of(
                    AMOUNT_ID, field(AMOUNT_ID, DATASET_ID, "DECIMAL(18,2)"),
                    STATUS_ID, field(STATUS_ID, DATASET_ID, "VARCHAR"));

    @Test
    void notInRequiresAtLeastOneValue() {
        var operator = FilterExpression.FilterOperator.valueOf("NOT_IN");
        assertThatCode(
                        () ->
                                validator.validate(
                                        DATASET_ID,
                                        predicate(
                                                STATUS_ID,
                                                operator,
                                                JsonNodeFactory.instance.textNode("DRAFT"),
                                                JsonNodeFactory.instance.textNode("CANCELLED")),
                                        fields))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        DATASET_ID, predicate(STATUS_ID, operator), fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one value");
    }

    @Test
    void acceptsNestedGroupsWithTypeCompatiblePredicates() {
        FilterExpression expression =
                new FilterExpression(
                        FilterExpression.LogicalOperator.AND,
                        List.of(
                                predicate(
                                        STATUS_ID,
                                        CONTAINS,
                                        JsonNodeFactory.instance.textNode("paid")),
                                predicate(
                                        AMOUNT_ID,
                                        BETWEEN,
                                        JsonNodeFactory.instance.numberNode(10),
                                        JsonNodeFactory.instance.numberNode(100))),
                        null,
                        null,
                        List.of());

        assertThatCode(() -> validator.validate(DATASET_ID, expression, fields))
                .doesNotThrowAnyException();
    }

    @Test
    void betweenRequiresExactlyTwoValues() {
        FilterExpression expression =
                predicate(AMOUNT_ID, BETWEEN, JsonNodeFactory.instance.numberNode(10));

        assertThatThrownBy(() -> validator.validate(DATASET_ID, expression, fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two values");
    }

    @Test
    void nullPredicateRejectsValues() {
        FilterExpression expression =
                predicate(STATUS_ID, IS_NULL, JsonNodeFactory.instance.textNode("unexpected"));

        assertThatThrownBy(() -> validator.validate(DATASET_ID, expression, fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not accept values");
    }

    @Test
    void containsRequiresAStringFieldFromTheDataset() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        DATASET_ID,
                                        predicate(
                                                AMOUNT_ID,
                                                CONTAINS,
                                                JsonNodeFactory.instance.textNode("10")),
                                        fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string field");

        DatasetFieldEntity foreign = field(STATUS_ID, OTHER_DATASET_ID, "VARCHAR");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        DATASET_ID,
                                        predicate(
                                                STATUS_ID,
                                                CONTAINS,
                                                JsonNodeFactory.instance.textNode("paid")),
                                        Map.of(STATUS_ID, foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same dataset");
    }

    private static FilterExpression predicate(
            UUID fieldId,
            FilterExpression.FilterOperator operator,
            com.fasterxml.jackson.databind.JsonNode... values) {
        return new FilterExpression(null, List.of(), fieldId, operator, List.of(values));
    }

    private static DatasetFieldEntity field(UUID id, UUID datasetId, String type) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(id);
        field.setDatasetId(datasetId);
        field.setStarrocksType(type);
        field.setSelected(true);
        return field;
    }
}
