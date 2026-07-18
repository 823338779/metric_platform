package com.metricplatform.metadata.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaDriftServiceTest {
    private static final UUID DATASET_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID REMOVED_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID CHANGED_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Mock DatasetMapper datasets;
    @Mock DatasetFieldMapper fields;
    @Mock DimensionMapper dimensions;
    @Mock MetricMapper metrics;
    @Mock DatasetRelationshipFieldPairMapper relationshipPairs;
    @Mock SourceMetadataInspector inspector;

    @Test
    void detectsChangesAndBlocksSynchronizationThatWouldBreakADimension() {
        DatasetEntity dataset = dataset();
        DatasetFieldEntity removed = field(REMOVED_ID, "legacy_code", "VARCHAR");
        DatasetFieldEntity changed = field(CHANGED_ID, "amount", "BIGINT");
        DimensionEntity dimension = new DimensionEntity();
        dimension.setId(UUID.randomUUID());
        dimension.setDatasetId(DATASET_ID);
        dimension.setDatasetFieldId(REMOVED_ID);
        dimension.setName("legacy");

        when(datasets.selectById(DATASET_ID)).thenReturn(dataset);
        when(fields.selectList(any())).thenReturn(List.of(removed, changed));
        when(dimensions.selectList(any())).thenReturn(List.of(dimension));
        when(metrics.selectList(any())).thenReturn(List.of());
        when(inspector.inspectObject(SOURCE_ID, "dw", "fact_order"))
                .thenReturn(
                        new SourceMetadataInspector.SourceObjectDefinition(
                                "dw",
                                "fact_order",
                                "TABLE",
                                List.of(
                                        new SourceMetadataInspector.SourceColumn(
                                                "amount", "DECIMAL(18,2)", 1),
                                        new SourceMetadataInspector.SourceColumn(
                                                "created_at", "DATETIME", 2))));

        SchemaDriftService service =
                new SchemaDriftService(
                        datasets,
                        fields,
                        dimensions,
                        metrics,
                        relationshipPairs,
                        inspector,
                        new ObjectMapper());

        DatasetDtos.SchemaDiffResponse diff = service.detect(DATASET_ID);

        assertThat(diff.added())
                .extracting(DatasetDtos.FieldChange::physicalName)
                .containsExactly("created_at");
        assertThat(diff.removed())
                .extracting(DatasetDtos.FieldChange::physicalName)
                .containsExactly("legacy_code");
        assertThat(diff.typeChanged())
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.previousType()).isEqualTo("BIGINT");
                            assertThat(change.currentType()).isEqualTo("DECIMAL(18,2)");
                        });
        assertThat(diff.blockingImpacts()).singleElement().isEqualTo("dimension:legacy");

        assertThatThrownBy(
                        () ->
                                service.sync(
                                        DATASET_ID,
                                        new DatasetDtos.SyncFieldsRequest(0, diff.fingerprint())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("dependent semantic objects");

        verify(fields, never()).insert(any(DatasetFieldEntity.class));
        verify(fields, never()).updateById(any(DatasetFieldEntity.class));
        verify(fields, never()).deleteById(any(UUID.class));
    }

    private static DatasetEntity dataset() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(DATASET_ID);
        dataset.setDataSourceId(SOURCE_ID);
        dataset.setSchemaName("dw");
        dataset.setObjectName("fact_order");
        dataset.setObjectType("TABLE");
        dataset.setVersion(0);
        return dataset;
    }

    private static DatasetFieldEntity field(UUID id, String name, String type) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(id);
        field.setDatasetId(DATASET_ID);
        field.setPhysicalName(name);
        field.setDisplayName(name);
        field.setStarrocksType(type);
        field.setSelected(true);
        return field;
    }
}
