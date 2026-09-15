package com.metricplatform.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetFieldPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipFieldPairPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionTimeGranularityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticCatalogReaderTest {
    private final DatasetEntityPersistenceService datasets =
            mock(DatasetEntityPersistenceService.class);
    private final DatasetFieldPersistenceService fields =
            mock(DatasetFieldPersistenceService.class);
    private final MetricEntityPersistenceService metrics =
            mock(MetricEntityPersistenceService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticCatalogReader reader =
            new SemanticCatalogReader(
                    datasets,
                    fields,
                    metrics,
                    mock(DimensionEntityPersistenceService.class),
                    mock(DimensionTimeGranularityPersistenceService.class),
                    mock(DatasetRelationshipEntityPersistenceService.class),
                    mock(DatasetRelationshipFieldPairPersistenceService.class),
                    mapper);

    @Test
    void loadsNestedMetricFilterFieldsEvenWhenTheyAreNotProjected() throws Exception {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(UUID.randomUUID());
        DatasetFieldEntity amount = field(dataset.getId());
        DatasetFieldEntity status = field(dataset.getId());
        DatasetFieldEntity channel = field(dataset.getId());
        MetricEntity metric = metric(dataset, amount);
        metric.setFixedFilterExpression(
                mapper.writeValueAsString(
                        new FilterExpression(
                                FilterExpression.LogicalOperator.AND,
                                List.of(
                                        predicate(status),
                                        new FilterExpression(
                                                FilterExpression.LogicalOperator.AND,
                                                List.of(predicate(channel)),
                                                null,
                                                null,
                                                List.of())),
                                null,
                                null,
                                List.of())));

        var definition = reader.metric(metric.getId());

        assertThat(definition.filterFields()).containsOnlyKeys(status.getId(), channel.getId());
        assertThat(definition.field()).isSameAs(amount);
    }

    @Test
    void rejectsFixedFilterFieldFromAnotherDataset() throws Exception {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(UUID.randomUUID());
        MetricEntity metric = metric(dataset, field(dataset.getId()));
        metric.setFixedFilterExpression(
                mapper.writeValueAsString(predicate(field(UUID.randomUUID()))));

        assertThatThrownBy(() -> reader.metric(metric.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same dataset");
    }

    @Test
    void legacyMetricHasNoFixedFilterFields() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(UUID.randomUUID());
        MetricEntity metric = metric(dataset, field(dataset.getId()));

        assertThat(reader.metric(metric.getId()).filterFields()).isEmpty();
    }

    private MetricEntity metric(DatasetEntity dataset, DatasetFieldEntity field) {
        MetricEntity metric = new MetricEntity();
        metric.setId(UUID.randomUUID());
        metric.setDatasetId(dataset.getId());
        metric.setDatasetFieldId(field.getId());
        when(metrics.getById(metric.getId())).thenReturn(metric);
        when(datasets.getById(dataset.getId())).thenReturn(dataset);
        return metric;
    }

    private DatasetFieldEntity field(UUID datasetId) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(UUID.randomUUID());
        field.setDatasetId(datasetId);
        when(fields.getById(field.getId())).thenReturn(field);
        return field;
    }

    private static FilterExpression predicate(DatasetFieldEntity field) {
        return new FilterExpression(
                null,
                List.of(),
                field.getId(),
                FilterExpression.FilterOperator.EQ,
                List.of(JsonNodeFactory.instance.textNode("value")));
    }
}
