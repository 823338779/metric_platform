package com.metricplatform.metadata.dataset;

import static com.metricplatform.metadata.dataset.DatasetDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipFieldPairEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchemaDriftService {
    private final DatasetMapper datasets;
    private final DatasetFieldMapper fields;
    private final DimensionMapper dimensions;
    private final MetricMapper metrics;
    private final DatasetRelationshipFieldPairMapper relationshipPairs;
    private final SourceMetadataInspector inspector;
    private final ObjectMapper objectMapper;

    public SchemaDiffResponse detect(UUID datasetId) {
        DatasetEntity dataset = dataset(datasetId);
        List<DatasetFieldEntity> persisted = fields(datasetId);
        SourceMetadataInspector.SourceObjectDefinition source =
                inspector.inspectObject(
                        dataset.getDataSourceId(),
                        dataset.getSchemaName(),
                        dataset.getObjectName());

        Map<String, DatasetFieldEntity> persistedByName =
                persisted.stream()
                        .collect(
                                Collectors.toMap(
                                        field -> key(field.getPhysicalName()), field -> field));
        Map<String, SourceMetadataInspector.SourceColumn> sourceByName =
                source.columns().stream()
                        .collect(Collectors.toMap(column -> key(column.name()), column -> column));

        List<FieldChange> added =
                source.columns().stream()
                        .filter(column -> !persistedByName.containsKey(key(column.name())))
                        .map(column -> new FieldChange(null, column.name(), null, column.type()))
                        .toList();
        List<FieldChange> removed =
                persisted.stream()
                        .filter(field -> !sourceByName.containsKey(key(field.getPhysicalName())))
                        .map(
                                field ->
                                        new FieldChange(
                                                field.getId(),
                                                field.getPhysicalName(),
                                                field.getStarrocksType(),
                                                null))
                        .toList();
        List<FieldChange> changed =
                persisted.stream()
                        .filter(field -> sourceByName.containsKey(key(field.getPhysicalName())))
                        .filter(
                                field ->
                                        !field.getStarrocksType()
                                                .equalsIgnoreCase(
                                                        sourceByName
                                                                .get(key(field.getPhysicalName()))
                                                                .type()))
                        .map(
                                field ->
                                        new FieldChange(
                                                field.getId(),
                                                field.getPhysicalName(),
                                                field.getStarrocksType(),
                                                sourceByName
                                                        .get(key(field.getPhysicalName()))
                                                        .type()))
                        .toList();

        Set<UUID> invalidatedFieldIds =
                java.util.stream.Stream.concat(removed.stream(), changed.stream())
                        .map(FieldChange::fieldId)
                        .collect(Collectors.toSet());
        List<String> impacts = impacts(invalidatedFieldIds);
        String fingerprint = fingerprint(added, removed, changed);
        return new SchemaDiffResponse(added, removed, changed, impacts, fingerprint);
    }

    @Transactional
    public SchemaDiffResponse sync(UUID datasetId, SyncFieldsRequest request) {
        DatasetEntity dataset = dataset(datasetId);
        if (dataset.getVersion() != request.version()) {
            throw new ConflictException("dataset was modified by another user");
        }
        SchemaDiffResponse diff = detect(datasetId);
        if (!diff.fingerprint().equals(request.fingerprint())) {
            throw new ConflictException("source schema changed after the preview");
        }
        if (!diff.blockingImpacts().isEmpty()) {
            throw new ConflictException("field changes would break dependent semantic objects");
        }

        Map<UUID, DatasetFieldEntity> persistedById =
                fields(datasetId).stream()
                        .collect(Collectors.toMap(DatasetFieldEntity::getId, field -> field));
        for (FieldChange removed : diff.removed()) {
            fields.softDeleteById(removed.fieldId());
        }
        for (FieldChange changed : diff.typeChanged()) {
            DatasetFieldEntity field = persistedById.get(changed.fieldId());
            field.setStarrocksType(changed.currentType());
            if (fields.updateById(field) == 0) {
                throw new ConflictException("dataset field was modified by another user");
            }
        }
        for (FieldChange added : diff.added()) {
            DatasetFieldEntity field = new DatasetFieldEntity();
            field.setId(UUID.randomUUID());
            field.setDatasetId(datasetId);
            field.setPhysicalName(added.physicalName());
            field.setDisplayName(added.physicalName());
            field.setStarrocksType(added.currentType());
            field.setDimensionEnabled(true);
            field.setMetricEnabled(true);
            field.setSelected(false);
            field.setVersion(0);
            fields.insert(field);
        }
        if (datasets.updateById(dataset) == 0) {
            throw new ConflictException("dataset was modified by another user");
        }
        return detect(datasetId);
    }

    private List<String> impacts(Set<UUID> invalidatedFieldIds) {
        if (invalidatedFieldIds.isEmpty()) return List.of();
        List<String> impacts = new ArrayList<>();
        dimensions.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(dimension -> invalidatedFieldIds.contains(dimension.getDatasetFieldId()))
                .map(dimension -> "dimension:" + dimension.getName())
                .forEach(impacts::add);
        metrics.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(
                        metric ->
                                metric.getDatasetFieldId() != null
                                        && invalidatedFieldIds.contains(metric.getDatasetFieldId()))
                .map(metric -> "metric:" + metric.getName())
                .forEach(impacts::add);
        relationshipPairs.selectAll().stream()
                .filter(
                        pair ->
                                invalidatedFieldIds.contains(pair.getSourceFieldId())
                                        || invalidatedFieldIds.contains(pair.getTargetFieldId()))
                .map(DatasetRelationshipFieldPairEntity::getRelationshipId)
                .distinct()
                .map(id -> "relationship:" + id)
                .forEach(impacts::add);
        return impacts.stream().sorted().toList();
    }

    private String fingerprint(
            List<FieldChange> added, List<FieldChange> removed, List<FieldChange> changed) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(List.of(added, removed, changed));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("schema diff fingerprint failed", exception);
        }
    }

    private DatasetEntity dataset(UUID id) {
        DatasetEntity dataset = datasets.selectById(id);
        if (dataset == null) throw new NoSuchElementException("dataset not found");
        return dataset;
    }

    private List<DatasetFieldEntity> fields(UUID datasetId) {
        return fields.selectList(
                new LambdaQueryWrapper<DatasetFieldEntity>()
                        .eq(DatasetFieldEntity::getDatasetId, datasetId)
                        .orderByAsc(DatasetFieldEntity::getPhysicalName));
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
