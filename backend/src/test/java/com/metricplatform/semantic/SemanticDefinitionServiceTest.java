package com.metricplatform.semantic;

import static com.metricplatform.semantic.SemanticDtos.Aggregation.*;
import static com.metricplatform.semantic.SemanticDtos.DimensionType.STANDARD;
import static com.metricplatform.semantic.SemanticDtos.DimensionType.TIME;
import static com.metricplatform.semantic.SemanticDtos.TimeGranularity.DAY;
import static com.metricplatform.semantic.SemanticDtos.TimeGranularity.MONTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.DimensionTimeGranularityMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import com.metricplatform.persistence.mapper.SemanticAliasMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticDefinitionServiceTest {
    private final DatasetMapper datasets = mock(DatasetMapper.class);
    private final DatasetFieldMapper fields = mock(DatasetFieldMapper.class);
    private final DimensionMapper dimensions = mock(DimensionMapper.class);
    private final DimensionTimeGranularityMapper granularities =
            mock(DimensionTimeGranularityMapper.class);
    private final MetricMapper metrics = mock(MetricMapper.class);
    private final SemanticAliasMapper aliases = mock(SemanticAliasMapper.class);
    private final SemanticDefinitionService service =
            new SemanticDefinitionService(
                    datasets, fields, dimensions, granularities, metrics, aliases);
    private final UUID datasetId = UUID.randomUUID();
    private final UUID fieldId = UUID.randomUUID();

    @Test
    void countMayBeFieldlessButOtherAggregationsRequireAField() {
        datasetExists();
        SemanticDtos.MetricResponse count =
                service.createMetric(
                        new SemanticDtos.CreateMetricRequest(
                                datasetId, null, "orders", "Orders", COUNT));
        assertThat(count.datasetFieldId()).isNull();

        assertThatThrownBy(
                        () ->
                                service.createMetric(
                                        new SemanticDtos.CreateMetricRequest(
                                                datasetId, null, "sales", "Sales", SUM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a field");
    }

    @Test
    void sumAndAvgRequireNumericMetricEnabledFields() {
        datasetExists();
        when(fields.selectById(fieldId)).thenReturn(field("VARCHAR", true, true));
        assertThatThrownBy(
                        () ->
                                service.createMetric(
                                        new SemanticDtos.CreateMetricRequest(
                                                datasetId, fieldId, "sales", "Sales", AVG)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric");

        when(fields.selectById(fieldId)).thenReturn(field("DECIMAL(18,2)", true, true));
        assertThat(
                        service.createMetric(
                                        new SemanticDtos.CreateMetricRequest(
                                                datasetId, fieldId, "sales", "Sales", SUM))
                                .aggregation())
                .isEqualTo(SUM);
    }

    @Test
    void timeDimensionsRequireTemporalFieldsAndAtLeastOneGranularity() {
        datasetExists();
        when(fields.selectById(fieldId)).thenReturn(field("VARCHAR", true, true));
        assertThatThrownBy(
                        () ->
                                service.createDimension(
                                        new SemanticDtos.CreateDimensionRequest(
                                                datasetId,
                                                fieldId,
                                                "created",
                                                "Created",
                                                TIME,
                                                Set.of(DAY))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date or timestamp");

        when(fields.selectById(fieldId)).thenReturn(field("DATETIME", true, true));
        assertThatThrownBy(
                        () ->
                                service.createDimension(
                                        new SemanticDtos.CreateDimensionRequest(
                                                datasetId, fieldId, "created", "Created", TIME,
                                                Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("granularity");

        SemanticDtos.DimensionResponse response =
                service.createDimension(
                        new SemanticDtos.CreateDimensionRequest(
                                datasetId,
                                fieldId,
                                "created",
                                "Created",
                                TIME,
                                Set.of(DAY, MONTH)));
        assertThat(response.allowedGranularities()).containsExactlyInAnyOrder(DAY, MONTH);
        verify(granularities, org.mockito.Mockito.times(2)).insertGranularity(any());
    }

    @Test
    void rejectsStandardDimensionGranularitiesAndStaleMetricUpdates() {
        datasetExists();
        when(fields.selectById(fieldId)).thenReturn(field("BIGINT", true, true));
        assertThatThrownBy(
                        () ->
                                service.createDimension(
                                        new SemanticDtos.CreateDimensionRequest(
                                                datasetId,
                                                fieldId,
                                                "customer",
                                                "Customer",
                                                STANDARD,
                                                Set.of(DAY))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STANDARD");

        MetricEntity metric = new MetricEntity();
        metric.setId(UUID.randomUUID());
        metric.setDatasetId(datasetId);
        metric.setVersion(3);
        when(metrics.selectById(metric.getId())).thenReturn(metric);
        assertThatThrownBy(
                        () ->
                                service.updateMetric(
                                        metric.getId(),
                                        new SemanticDtos.UpdateMetricRequest(
                                                fieldId, "sales", "Sales", MAX, 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("stale");
    }

    private void datasetExists() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(datasetId);
        when(datasets.selectById(datasetId)).thenReturn(dataset);
    }

    private DatasetFieldEntity field(String type, boolean metric, boolean dimension) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(fieldId);
        field.setDatasetId(datasetId);
        field.setStarrocksType(type);
        field.setMetricEnabled(metric);
        field.setDimensionEnabled(dimension);
        field.setSelected(true);
        return field;
    }
}
