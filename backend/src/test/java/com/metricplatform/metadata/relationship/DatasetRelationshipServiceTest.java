package com.metricplatform.metadata.relationship;

import static com.metricplatform.metadata.relationship.RelationshipDtos.Cardinality.MANY_TO_ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatasetRelationshipServiceTest {
    private static final UUID SOURCE_DATASET =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_DATASET =
            UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID DATA_SOURCE = UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final UUID SOURCE_FIELD =
            UUID.fromString("50000000-0000-0000-0000-000000000004");
    private static final UUID TARGET_FIELD =
            UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Mock DatasetMapper datasets;
    @Mock DatasetFieldMapper fields;
    @Mock DatasetRelationshipMapper relationships;
    @Mock DatasetRelationshipFieldPairMapper pairs;
    @Mock RelationshipCardinalityValidator cardinalityValidator;

    @Test
    void persistsAValidatedCompositeRelationship() {
        when(datasets.selectById(SOURCE_DATASET)).thenReturn(dataset(SOURCE_DATASET, DATA_SOURCE));
        when(datasets.selectById(TARGET_DATASET)).thenReturn(dataset(TARGET_DATASET, DATA_SOURCE));
        when(fields.selectById(SOURCE_FIELD))
                .thenReturn(field(SOURCE_FIELD, SOURCE_DATASET, "BIGINT"));
        when(fields.selectById(TARGET_FIELD))
                .thenReturn(field(TARGET_FIELD, TARGET_DATASET, "BIGINT"));

        DatasetRelationshipService service = service();
        RelationshipDtos.RelationshipResponse response =
                service.create(
                        new RelationshipDtos.CreateRelationshipRequest(
                                "Order customer",
                                SOURCE_DATASET,
                                TARGET_DATASET,
                                MANY_TO_ONE,
                                List.of(
                                        new RelationshipDtos.FieldPairRequest(
                                                SOURCE_FIELD, TARGET_FIELD))));

        assertThat(response.sourceDatasetId()).isEqualTo(SOURCE_DATASET);
        assertThat(response.targetDatasetId()).isEqualTo(TARGET_DATASET);
        assertThat(response.joinType()).isEqualTo("LEFT");
        assertThat(response.fieldPairs()).hasSize(1);
        verify(relationships).insert(any(DatasetRelationshipEntity.class));
        verify(pairs).insert(any());
        verify(cardinalityValidator).validate(any(), any(), any());
    }

    @Test
    void rejectsCrossSourceAndWrongEndpointFieldsBeforeWriting() {
        when(datasets.selectById(SOURCE_DATASET)).thenReturn(dataset(SOURCE_DATASET, DATA_SOURCE));
        when(datasets.selectById(TARGET_DATASET))
                .thenReturn(dataset(TARGET_DATASET, UUID.randomUUID()));

        DatasetRelationshipService service = service();
        RelationshipDtos.CreateRelationshipRequest request =
                new RelationshipDtos.CreateRelationshipRequest(
                        "Invalid",
                        SOURCE_DATASET,
                        TARGET_DATASET,
                        MANY_TO_ONE,
                        List.of(new RelationshipDtos.FieldPairRequest(SOURCE_FIELD, TARGET_FIELD)));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same data source");
        verify(relationships, never()).insert(any(DatasetRelationshipEntity.class));
    }

    @Test
    void rejectsSameEndpointAndEmptyPairs() {
        when(datasets.selectById(SOURCE_DATASET)).thenReturn(dataset(SOURCE_DATASET, DATA_SOURCE));

        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                new RelationshipDtos.CreateRelationshipRequest(
                                                        "Self",
                                                        SOURCE_DATASET,
                                                        SOURCE_DATASET,
                                                        MANY_TO_ONE,
                                                        List.of(
                                                                new RelationshipDtos
                                                                        .FieldPairRequest(
                                                                        SOURCE_FIELD,
                                                                        TARGET_FIELD)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different datasets");

        when(datasets.selectById(TARGET_DATASET)).thenReturn(dataset(TARGET_DATASET, DATA_SOURCE));
        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                new RelationshipDtos.CreateRelationshipRequest(
                                                        "Empty",
                                                        SOURCE_DATASET,
                                                        TARGET_DATASET,
                                                        MANY_TO_ONE,
                                                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field pair");
    }

    @Test
    void rejectsWrongEndpointIncompatibleAndDuplicateFields() {
        when(datasets.selectById(SOURCE_DATASET)).thenReturn(dataset(SOURCE_DATASET, DATA_SOURCE));
        when(datasets.selectById(TARGET_DATASET)).thenReturn(dataset(TARGET_DATASET, DATA_SOURCE));
        when(fields.selectById(SOURCE_FIELD))
                .thenReturn(field(SOURCE_FIELD, TARGET_DATASET, "BIGINT"));
        when(fields.selectById(TARGET_FIELD))
                .thenReturn(field(TARGET_FIELD, TARGET_DATASET, "BIGINT"));

        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                request(
                                                        new RelationshipDtos.FieldPairRequest(
                                                                SOURCE_FIELD, TARGET_FIELD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source field");

        when(fields.selectById(SOURCE_FIELD))
                .thenReturn(field(SOURCE_FIELD, SOURCE_DATASET, "DATE"));
        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                request(
                                                        new RelationshipDtos.FieldPairRequest(
                                                                SOURCE_FIELD, TARGET_FIELD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible");

        when(fields.selectById(SOURCE_FIELD))
                .thenReturn(field(SOURCE_FIELD, SOURCE_DATASET, "BIGINT"));
        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                new RelationshipDtos.CreateRelationshipRequest(
                                                        "Duplicate",
                                                        SOURCE_DATASET,
                                                        TARGET_DATASET,
                                                        MANY_TO_ONE,
                                                        List.of(
                                                                new RelationshipDtos
                                                                        .FieldPairRequest(
                                                                        SOURCE_FIELD, TARGET_FIELD),
                                                                new RelationshipDtos
                                                                        .FieldPairRequest(
                                                                        SOURCE_FIELD,
                                                                        UUID.randomUUID())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static RelationshipDtos.CreateRelationshipRequest request(
            RelationshipDtos.FieldPairRequest pair) {
        return new RelationshipDtos.CreateRelationshipRequest(
                "Relationship", SOURCE_DATASET, TARGET_DATASET, MANY_TO_ONE, List.of(pair));
    }

    private DatasetRelationshipService service() {
        return new DatasetRelationshipService(
                datasets, fields, relationships, pairs, cardinalityValidator);
    }

    private static DatasetEntity dataset(UUID id, UUID sourceId) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(id);
        dataset.setDataSourceId(sourceId);
        dataset.setSchemaName("dw");
        dataset.setObjectName(id.equals(SOURCE_DATASET) ? "fact_order" : "dim_customer");
        return dataset;
    }

    private static DatasetFieldEntity field(UUID id, UUID datasetId, String type) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(id);
        field.setDatasetId(datasetId);
        field.setPhysicalName("id");
        field.setStarrocksType(type);
        field.setSelected(true);
        return field;
    }
}
