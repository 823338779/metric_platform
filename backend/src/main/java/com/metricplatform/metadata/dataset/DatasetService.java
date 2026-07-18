package com.metricplatform.metadata.dataset;

import static com.metricplatform.metadata.dataset.DatasetDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetService {
    private final DataSourceMapper sources;
    private final DatasetMapper datasets;
    private final DatasetFieldMapper fields;
    private final MetricMapper metrics;
    private final DimensionMapper dimensions;
    private final DatasetRelationshipMapper relationships;
    private final SourceMetadataInspector sourceMetadata;
    private final FilterValidator filterValidator;
    private final ObjectMapper objectMapper;

    public DatasetService(
            DataSourceMapper sources,
            DatasetMapper datasets,
            DatasetFieldMapper fields,
            MetricMapper metrics,
            DimensionMapper dimensions,
            DatasetRelationshipMapper relationships,
            SourceMetadataInspector sourceMetadata,
            FilterValidator filterValidator,
            ObjectMapper objectMapper) {
        this.sources = sources;
        this.datasets = datasets;
        this.fields = fields;
        this.metrics = metrics;
        this.dimensions = dimensions;
        this.relationships = relationships;
        this.sourceMetadata = sourceMetadata;
        this.filterValidator = filterValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DatasetResponse create(CreateDatasetRequest request) {
        var source = sources.selectById(request.dataSourceId());
        if (source == null) {
            throw new NoSuchElementException("data source not found");
        }
        if (!"ACTIVE".equals(source.getStatus()) && !"ENABLED".equals(source.getStatus())) {
            throw new IllegalStateException("data source is not active");
        }
        SourceMetadataInspector.SourceObjectDefinition object =
                sourceMetadata.inspectObject(
                        request.dataSourceId(), request.schemaName(), request.objectName());
        Map<String, SourceMetadataInspector.SourceColumn> available = new HashMap<>();
        object.columns()
                .forEach(column -> available.put(column.name().toLowerCase(Locale.ROOT), column));
        Set<String> selected = new LinkedHashSet<>();
        for (String physicalName : request.selectedPhysicalFields()) {
            String key = physicalName.toLowerCase(Locale.ROOT);
            if (!selected.add(key)) {
                throw new IllegalArgumentException("selected physical fields must be unique");
            }
            if (!available.containsKey(key)) {
                throw new IllegalArgumentException("unknown physical field: " + physicalName);
            }
        }

        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(UUID.randomUUID());
        dataset.setDataSourceId(request.dataSourceId());
        dataset.setName(request.name().trim());
        dataset.setDescription(trimToNull(request.description()));
        dataset.setSchemaName(object.schema());
        dataset.setObjectName(object.name());
        dataset.setObjectType(object.type());
        dataset.setVersion(0);
        datasets.insert(dataset);

        for (String key : selected) {
            SourceMetadataInspector.SourceColumn column = available.get(key);
            DatasetFieldEntity field = new DatasetFieldEntity();
            field.setId(UUID.randomUUID());
            field.setDatasetId(dataset.getId());
            field.setPhysicalName(column.name());
            field.setDisplayName(column.name());
            field.setStarrocksType(column.type());
            field.setDimensionEnabled(true);
            field.setMetricEnabled(true);
            field.setSelected(true);
            field.setVersion(0);
            fields.insert(field);
        }
        return response(dataset);
    }

    public PageResponse<DatasetResponse> list(String q, UUID dataSourceId, int page, int size) {
        List<DatasetEntity> matching =
                datasets.selectList(null).stream()
                        .filter(
                                dataset ->
                                        dataSourceId == null
                                                || dataSourceId.equals(dataset.getDataSourceId()))
                        .filter(
                                dataset ->
                                        q == null
                                                || q.isBlank()
                                                || dataset.getName()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(q.toLowerCase(Locale.ROOT)))
                        .sorted(Comparator.comparing(DatasetEntity::getName))
                        .toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return new PageResponse<>(
                matching.subList(from, to).stream().map(this::response).toList(),
                matching.size(),
                page,
                size);
    }

    public DatasetResponse get(UUID id) {
        return response(entity(id));
    }

    @Transactional
    public DatasetResponse update(UUID id, UpdateDatasetRequest request) {
        DatasetEntity dataset = entity(id);
        if (dataset.getVersion() != request.version()) {
            throw new ConflictException("dataset was modified by another user");
        }
        List<DatasetFieldEntity> currentFields = fieldEntities(id);
        Map<UUID, DatasetFieldEntity> byId = new HashMap<>();
        currentFields.forEach(field -> byId.put(field.getId(), field));
        filterValidator.validate(id, request.filterExpression(), byId);

        for (FieldUpdate fieldUpdate :
                request.fieldUpdates() == null ? List.<FieldUpdate>of() : request.fieldUpdates()) {
            DatasetFieldEntity field = byId.get(fieldUpdate.fieldId());
            if (field == null) {
                throw new IllegalArgumentException("field must belong to the dataset");
            }
            field.setDisplayName(fieldUpdate.displayName().trim());
            field.setSelected(fieldUpdate.selected());
            if (fields.updateById(field) == 0) {
                throw new ConflictException("dataset field was modified by another user");
            }
        }

        dataset.setName(request.name().trim());
        dataset.setDescription(trimToNull(request.description()));
        dataset.setFilterExpression(writeFilter(request.filterExpression()));
        if (datasets.updateById(dataset) == 0) {
            throw new ConflictException("dataset was modified by another user");
        }
        return response(entity(id));
    }

    public DeletionImpactResponse deletionImpact(UUID id) {
        entity(id);
        int fieldCount = fieldEntities(id).size();
        int metricCount =
                metrics.selectCount(
                                new LambdaQueryWrapper<
                                                com.metricplatform.persistence.entity
                                                        .MetricEntity>()
                                        .eq(
                                                com.metricplatform.persistence.entity.MetricEntity
                                                        ::getDatasetId,
                                                id))
                        .intValue();
        int dimensionCount =
                dimensions
                        .selectCount(
                                new LambdaQueryWrapper<
                                                com.metricplatform.persistence.entity
                                                        .DimensionEntity>()
                                        .eq(
                                                com.metricplatform.persistence.entity
                                                                .DimensionEntity
                                                        ::getDatasetId,
                                                id))
                        .intValue();
        int relationshipCount =
                relationships
                        .selectCount(
                                new LambdaQueryWrapper<DatasetRelationshipEntity>()
                                        .and(
                                                wrapper ->
                                                        wrapper.eq(
                                                                        DatasetRelationshipEntity
                                                                                ::getSourceDatasetId,
                                                                        id)
                                                                .or()
                                                                .eq(
                                                                        DatasetRelationshipEntity
                                                                                ::getTargetDatasetId,
                                                                        id)))
                        .intValue();
        return new DeletionImpactResponse(
                fieldCount, metricCount, dimensionCount, relationshipCount);
    }

    @Transactional
    public void delete(UUID id, boolean cascade) {
        entity(id);
        if (!cascade) {
            throw new ConflictException("dataset deletion requires cascade confirmation");
        }
        datasets.softDeleteById(id);
    }

    public List<DatasetFieldResponse> fields(UUID datasetId) {
        entity(datasetId);
        return fieldEntities(datasetId).stream().map(DatasetService::fieldResponse).toList();
    }

    private DatasetEntity entity(UUID id) {
        DatasetEntity dataset = datasets.selectById(id);
        if (dataset == null) {
            throw new NoSuchElementException("dataset not found");
        }
        return dataset;
    }

    private List<DatasetFieldEntity> fieldEntities(UUID datasetId) {
        return fields.selectList(
                new LambdaQueryWrapper<DatasetFieldEntity>()
                        .eq(DatasetFieldEntity::getDatasetId, datasetId)
                        .orderByAsc(DatasetFieldEntity::getPhysicalName));
    }

    private DatasetResponse response(DatasetEntity dataset) {
        return new DatasetResponse(
                dataset.getId(),
                dataset.getDataSourceId(),
                dataset.getName(),
                dataset.getDescription(),
                dataset.getSchemaName(),
                dataset.getObjectName(),
                dataset.getObjectType(),
                readFilter(dataset.getFilterExpression()),
                dataset.getVersion(),
                fieldEntities(dataset.getId()).stream()
                        .map(DatasetService::fieldResponse)
                        .toList());
    }

    private static DatasetFieldResponse fieldResponse(DatasetFieldEntity field) {
        return new DatasetFieldResponse(
                field.getId(),
                field.getPhysicalName(),
                field.getDisplayName(),
                field.getStarrocksType(),
                field.isSelected(),
                field.getVersion());
    }

    private String writeFilter(FilterExpression filter) {
        if (filter == null) return null;
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "filter expression could not be serialized", exception);
        }
    }

    private FilterExpression readFilter(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, FilterExpression.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored filter expression is invalid", exception);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
