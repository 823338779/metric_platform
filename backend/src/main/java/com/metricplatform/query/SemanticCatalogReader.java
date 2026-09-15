package com.metricplatform.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.metadata.dataset.FilterExpression;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetFieldPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipFieldPairPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionTimeGranularityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import com.metricplatform.query.planner.DatasetRelationshipGraph;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SemanticCatalogReader {
    private static final String LEFT_JOIN_TYPE = "LEFT";
    private final DatasetEntityPersistenceService datasets;
    private final DatasetFieldPersistenceService fields;
    private final MetricEntityPersistenceService metrics;
    private final DimensionEntityPersistenceService dimensions;
    private final DimensionTimeGranularityPersistenceService granularities;
    private final DatasetRelationshipEntityPersistenceService relationships;
    private final DatasetRelationshipFieldPairPersistenceService relationshipPairs;
    private final ObjectMapper objectMapper;

    /** 已读取的指标元数据；filterFields 包含固定过滤引用的所有字段，避免编译阶段额外读取。 */
    public record MetricDefinition(
            MetricEntity metric,
            DatasetEntity dataset,
            DatasetFieldEntity field,
            Map<UUID, DatasetFieldEntity> filterFields) {
        public MetricDefinition {
            filterFields = Map.copyOf(filterFields);
        }

        public MetricDefinition(
                MetricEntity metric, DatasetEntity dataset, DatasetFieldEntity field) {
            this(metric, dataset, field, Map.of());
        }
    }

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
            DatasetEntity targetDataset,
            /** source 为左侧、target 为右侧；空值沿用人工关系的 LEFT 语义。 */
            String joinType) {
        public RelationshipDefinition {
            joinType = joinType == null ? LEFT_JOIN_TYPE : joinType;
        }

        public RelationshipDefinition(
                UUID id,
                UUID sourceDatasetId,
                UUID targetDatasetId,
                DatasetRelationshipGraph.Cardinality cardinality,
                List<FieldPair> fieldPairs,
                DatasetEntity sourceDataset,
                DatasetEntity targetDataset) {
            this(
                    id,
                    sourceDatasetId,
                    targetDatasetId,
                    cardinality,
                    fieldPairs,
                    sourceDataset,
                    targetDataset,
                    LEFT_JOIN_TYPE);
        }

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
        MetricEntity metric = required(metrics.getById(id), "metric", id);
        return new MetricDefinition(
                metric,
                dataset(metric.getDatasetId()),
                metric.getDatasetFieldId() == null ? null : field(metric.getDatasetFieldId()),
                metricFilterFields(metric));
    }

    private Map<UUID, DatasetFieldEntity> metricFilterFields(MetricEntity metric) {
        String json = metric.getFixedFilterExpression();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<UUID, DatasetFieldEntity> referenced = new LinkedHashMap<>();
            // 过滤字段可能不参与维度或度量投影，必须在元数据快照事务内完整读取。
            collectFilterFields(
                    objectMapper.readValue(json, FilterExpression.class),
                    metric.getDatasetId(),
                    referenced);
            return referenced;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metric fixed filter is not valid", exception);
        }
    }

    private void collectFilterFields(
            FilterExpression expression, UUID datasetId, Map<UUID, DatasetFieldEntity> referenced) {
        if (expression == null)
            throw new IllegalArgumentException("metric fixed filter is not valid");
        if (expression.logicalOperator() != null) {
            expression
                    .children()
                    .forEach(child -> collectFilterFields(child, datasetId, referenced));
            return;
        }
        DatasetFieldEntity field = referenced.computeIfAbsent(expression.fieldId(), this::field);
        if (!datasetId.equals(field.getDatasetId())) {
            throw new IllegalArgumentException(
                    "metric filter field must belong to the same dataset");
        }
    }

    public DimensionDefinition dimension(UUID id) {
        DimensionEntity dimension = required(dimensions.getById(id), "dimension", id);
        Set<TimeGranularity> allowed =
                granularities.listByDimensionId(id).stream()
                        .map(value -> TimeGranularity.valueOf(value.getGranularity()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new DimensionDefinition(
                dimension,
                dataset(dimension.getDatasetId()),
                field(dimension.getDatasetFieldId()),
                allowed);
    }

    public List<RelationshipDefinition> relationships() {
        return relationships.list().stream()
                .filter(entity -> "VALID".equals(entity.getLastValidationStatus()))
                .map(this::relationship)
                .toList();
    }

    private RelationshipDefinition relationship(DatasetRelationshipEntity entity) {
        List<FieldPair> pairs =
                relationshipPairs.listByRelationshipId(entity.getId()).stream()
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
                dataset(entity.getTargetDatasetId()),
                entity.getJoinType());
    }

    private DatasetEntity dataset(UUID id) {
        return required(datasets.getById(id), "dataset", id);
    }

    private DatasetFieldEntity field(UUID id) {
        return required(fields.getById(id), "dataset field", id);
    }

    private static <T> T required(T value, String type, UUID id) {
        if (value == null) throw new NoSuchElementException(type + " not found: " + id);
        return value;
    }
}
