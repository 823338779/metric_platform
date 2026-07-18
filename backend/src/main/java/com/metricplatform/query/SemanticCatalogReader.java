package com.metricplatform.query;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.DimensionTimeGranularityMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import com.metricplatform.query.planner.DatasetRelationshipGraph;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SemanticCatalogReader {
    private final DatasetMapper datasets;
    private final DatasetFieldMapper fields;
    private final MetricMapper metrics;
    private final DimensionMapper dimensions;
    private final DimensionTimeGranularityMapper granularities;
    private final DatasetRelationshipMapper relationships;
    private final DatasetRelationshipFieldPairMapper relationshipPairs;

    public record MetricDefinition(
            MetricEntity metric, DatasetEntity dataset, DatasetFieldEntity field) {}

    public record DimensionDefinition(
            DimensionEntity dimension,
            DatasetEntity dataset,
            DatasetFieldEntity field,
            Set<TimeGranularity> allowedGranularities) {}

    public record FieldPair(DatasetFieldEntity sourceField, DatasetFieldEntity targetField) {}

    public record RelationshipDefinition(
            UUID id,
            UUID sourceDatasetId,
            UUID targetDatasetId,
            DatasetRelationshipGraph.Cardinality cardinality,
            List<FieldPair> fieldPairs,
            DatasetEntity sourceDataset,
            DatasetEntity targetDataset) {
        public RelationshipDefinition(
                UUID id,
                UUID sourceDatasetId,
                UUID targetDatasetId,
                DatasetRelationshipGraph.Cardinality cardinality,
                List<FieldPair> fieldPairs) {
            this(id, sourceDatasetId, targetDatasetId, cardinality, fieldPairs, null, null);
        }
    }

    public MetricDefinition metric(UUID id) {
        MetricEntity metric = required(metrics.selectById(id), "metric", id);
        return new MetricDefinition(
                metric,
                dataset(metric.getDatasetId()),
                metric.getDatasetFieldId() == null ? null : field(metric.getDatasetFieldId()));
    }

    public DimensionDefinition dimension(UUID id) {
        DimensionEntity dimension = required(dimensions.selectById(id), "dimension", id);
        Set<TimeGranularity> allowed =
                granularities.selectByDimensionId(id).stream()
                        .map(value -> TimeGranularity.valueOf(value.getGranularity()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new DimensionDefinition(
                dimension,
                dataset(dimension.getDatasetId()),
                field(dimension.getDatasetFieldId()),
                allowed);
    }

    public List<RelationshipDefinition> relationships() {
        return relationships.selectList(null).stream()
                .filter(entity -> "VALID".equals(entity.getLastValidationStatus()))
                .map(this::relationship)
                .toList();
    }

    private RelationshipDefinition relationship(DatasetRelationshipEntity entity) {
        List<FieldPair> pairs =
                relationshipPairs.selectByRelationshipId(entity.getId()).stream()
                        .map(
                                pair ->
                                        new FieldPair(
                                                field(pair.getSourceFieldId()),
                                                field(pair.getTargetFieldId())))
                        .toList();
        return new RelationshipDefinition(
                entity.getId(),
                entity.getSourceDatasetId(),
                entity.getTargetDatasetId(),
                DatasetRelationshipGraph.Cardinality.valueOf(entity.getCardinality()),
                pairs,
                dataset(entity.getSourceDatasetId()),
                dataset(entity.getTargetDatasetId()));
    }

    private DatasetEntity dataset(UUID id) {
        return required(datasets.selectById(id), "dataset", id);
    }

    private DatasetFieldEntity field(UUID id) {
        return required(fields.selectById(id), "dataset field", id);
    }

    private static <T> T required(T value, String type, UUID id) {
        if (value == null) throw new NoSuchElementException(type + " not found: " + id);
        return value;
    }
}
