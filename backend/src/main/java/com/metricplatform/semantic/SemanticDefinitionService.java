package com.metricplatform.semantic;

import static com.metricplatform.semantic.SemanticDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.metadata.MetadataDtos.DimensionRequest;
import com.metricplatform.metadata.MetadataDtos.MetricRequest;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.DimensionTimeGranularityEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetFieldPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionTimeGranularityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SemanticDefinitionService {
    private static final Set<String> NUMERIC_TYPES =
            Set.of(
                    "TINYINT",
                    "SMALLINT",
                    "INT",
                    "INTEGER",
                    "BIGINT",
                    "LARGEINT",
                    "FLOAT",
                    "DOUBLE",
                    "DECIMAL",
                    "DECIMALV2",
                    "DECIMAL32",
                    "DECIMAL64",
                    "DECIMAL128");
    private static final Set<String> TEMPORAL_TYPES = Set.of("DATE", "DATETIME", "TIMESTAMP");

    private final DatasetEntityPersistenceService datasets;
    private final DatasetFieldPersistenceService fields;
    private final DimensionEntityPersistenceService dimensions;
    private final DimensionTimeGranularityPersistenceService granularities;
    private final MetricEntityPersistenceService metrics;
    private final ObjectMapper objectMapper;

    public PageResponse<MetricResponse> listMetrics(String q, UUID datasetId, int page, int size) {
        validatePage(page, size);
        List<MetricResponse> matches =
                metrics
                        .list(
                                new LambdaQueryWrapper<MetricEntity>()
                                        .orderByAsc(MetricEntity::getName))
                        .stream()
                        .filter(
                                metric ->
                                        datasetId == null
                                                || datasetId.equals(metric.getDatasetId()))
                        .filter(
                                metric ->
                                        matches(
                                                        q,
                                                        metric.getName(),
                                                        metric.getDisplayName(),
                                                        metric.getDescription(),
                                                        metric.getBusinessDefinition())
                                                || readAliases(metric.getAliases()).stream()
                                                        .anyMatch(value -> matches(q, value)))
                        .map(this::metricResponse)
                        .toList();
        return page(matches, page, size);
    }

    public MetricResponse getMetric(UUID id) {
        return metricResponse(metric(id));
    }

    @Transactional
    public MetricResponse createMetric(CreateMetricRequest request) {
        dataset(request.datasetId());
        validateUniqueMetricName(request.datasetId(), request.name(), null);
        DatasetFieldEntity field =
                validateMetricField(
                        request.datasetId(), request.datasetFieldId(), request.aggregation());
        MetricEntity metric = new MetricEntity();
        metric.setId(UUID.randomUUID());
        metric.setDatasetId(request.datasetId());
        metric.setDatasetFieldId(field == null ? null : field.getId());
        metric.setName(request.name().trim());
        metric.setDisplayName(request.displayName().trim());
        metric.setAggregation(request.aggregation().name());
        applyMetricMetadata(
                metric,
                request.description(),
                request.businessDefinition(),
                request.unit(),
                request.formatPattern(),
                request.status(),
                request.domain(),
                request.owner(),
                request.certificationStatus(),
                request.aggregationBehavior(),
                request.additivity(),
                request.metricType(),
                request.defaultTimeDimensionId());
        if (request.aliases() != null) metric.setAliases(writeAliases(request.aliases()));
        metrics.save(metric);
        return metricResponse(metric);
    }

    @Transactional
    public MetricResponse updateMetric(UUID id, UpdateMetricRequest request) {
        MetricEntity metric = metric(id);
        if (metric.getVersion() != request.version()) {
            throw new ConflictException("metric version is stale");
        }
        validateUniqueMetricName(metric.getDatasetId(), request.name(), id);
        DatasetFieldEntity field =
                validateMetricField(
                        metric.getDatasetId(), request.datasetFieldId(), request.aggregation());
        metric.setDatasetFieldId(field == null ? null : field.getId());
        metric.setName(request.name().trim());
        metric.setDisplayName(request.displayName().trim());
        metric.setAggregation(request.aggregation().name());
        applyMetricMetadata(
                metric,
                request.description(),
                request.businessDefinition(),
                request.unit(),
                request.formatPattern(),
                request.status(),
                request.domain(),
                request.owner(),
                request.certificationStatus(),
                request.aggregationBehavior(),
                request.additivity(),
                request.metricType(),
                request.defaultTimeDimensionId());
        if (request.aliases() != null) metric.setAliases(writeAliases(request.aliases()));
        if (!metrics.updateById(metric)) {
            throw new ConflictException("metric version is stale");
        }
        return metricResponse(metric);
    }

    @Transactional
    public void deleteMetric(UUID id) {
        metric(id);
        metrics.softDeleteById(id);
    }

    public DeletionImpactResponse metricDeletionImpact(UUID id) {
        metric(id);
        return new DeletionImpactResponse(id, true, List.of(), List.of());
    }

    public PageResponse<DimensionResponse> listDimensions(
            String q, UUID datasetId, int page, int size) {
        validatePage(page, size);
        List<DimensionResponse> matches =
                dimensions
                        .list(
                                new LambdaQueryWrapper<DimensionEntity>()
                                        .orderByAsc(DimensionEntity::getName))
                        .stream()
                        .filter(
                                dimension ->
                                        datasetId == null
                                                || datasetId.equals(dimension.getDatasetId()))
                        .filter(
                                dimension ->
                                        matches(
                                                        q,
                                                        dimension.getName(),
                                                        dimension.getDisplayName(),
                                                        dimension.getDescription(),
                                                        dimension.getBusinessDefinition())
                                                || readAliases(dimension.getAliases()).stream()
                                                        .anyMatch(value -> matches(q, value)))
                        .map(this::dimensionResponse)
                        .toList();
        return page(matches, page, size);
    }

    public DimensionResponse getDimension(UUID id) {
        return dimensionResponse(dimension(id));
    }

    @Transactional
    public DimensionResponse createDimension(CreateDimensionRequest request) {
        dataset(request.datasetId());
        validateUniqueDimension(
                request.datasetId(), request.datasetFieldId(), request.name(), null);
        validateDimensionField(
                request.datasetId(),
                request.datasetFieldId(),
                request.type(),
                request.allowedGranularities());
        DimensionEntity dimension = new DimensionEntity();
        dimension.setId(UUID.randomUUID());
        dimension.setDatasetId(request.datasetId());
        dimension.setDatasetFieldId(request.datasetFieldId());
        dimension.setName(request.name().trim());
        dimension.setDisplayName(request.displayName().trim());
        dimension.setDimensionType(request.type().name());
        applyDimensionMetadata(
                dimension,
                request.description(),
                request.businessDefinition(),
                request.domain(),
                request.owner(),
                request.status(),
                request.certificationStatus(),
                request.semanticType(),
                request.valueType(),
                request.defaultGranularity(),
                request.sensitivityLevel());
        if (request.aliases() != null) dimension.setAliases(writeAliases(request.aliases()));
        dimensions.save(dimension);
        replaceGranularities(dimension.getId(), request.allowedGranularities());
        return dimensionResponse(dimension, request.allowedGranularities());
    }

    @Transactional
    public DimensionResponse updateDimension(UUID id, UpdateDimensionRequest request) {
        DimensionEntity dimension = dimension(id);
        if (dimension.getVersion() != request.version()) {
            throw new ConflictException("dimension version is stale");
        }
        validateUniqueDimension(
                dimension.getDatasetId(), request.datasetFieldId(), request.name(), id);
        validateDimensionField(
                dimension.getDatasetId(),
                request.datasetFieldId(),
                request.type(),
                request.allowedGranularities());
        dimension.setDatasetFieldId(request.datasetFieldId());
        dimension.setName(request.name().trim());
        dimension.setDisplayName(request.displayName().trim());
        dimension.setDimensionType(request.type().name());
        applyDimensionMetadata(
                dimension,
                request.description(),
                request.businessDefinition(),
                request.domain(),
                request.owner(),
                request.status(),
                request.certificationStatus(),
                request.semanticType(),
                request.valueType(),
                request.defaultGranularity(),
                request.sensitivityLevel());
        if (request.aliases() != null) dimension.setAliases(writeAliases(request.aliases()));
        if (!dimensions.updateById(dimension)) {
            throw new ConflictException("dimension version is stale");
        }
        replaceGranularities(id, request.allowedGranularities());
        return dimensionResponse(dimension, request.allowedGranularities());
    }

    @Transactional
    public void deleteDimension(UUID id) {
        dimension(id);
        granularities.removeByDimensionId(id);
        dimensions.softDeleteById(id);
    }

    public DeletionImpactResponse dimensionDeletionImpact(UUID id) {
        dimension(id);
        List<DependentResource> dependencies = new ArrayList<>();
        datasets.list(
                        new LambdaQueryWrapper<DatasetEntity>()
                                .eq(DatasetEntity::getDefaultTimeDimensionId, id))
                .forEach(
                        dataset ->
                                dependencies.add(
                                        new DependentResource(
                                                "DATASET",
                                                dataset.getId(),
                                                dataset.getName(),
                                                "DEFAULT_TIME_DIMENSION")));
        metrics.list(
                        new LambdaQueryWrapper<MetricEntity>()
                                .eq(MetricEntity::getDefaultTimeDimensionId, id))
                .forEach(
                        metric ->
                                dependencies.add(
                                        new DependentResource(
                                                "METRIC",
                                                metric.getId(),
                                                metric.getName(),
                                                "DEFAULT_TIME_DIMENSION")));
        datasets.list().stream()
                .filter(
                        dataset ->
                                dataset.getFilterExpression() != null
                                        && dataset.getFilterExpression().contains(id.toString()))
                .forEach(
                        dataset ->
                                dependencies.add(
                                        new DependentResource(
                                                "DATASET",
                                                dataset.getId(),
                                                dataset.getName(),
                                                "FILTER_REFERENCE")));
        return new DeletionImpactResponse(
                id,
                dependencies.isEmpty(),
                dependencies,
                dependencies.isEmpty()
                        ? List.of()
                        : List.of("Dependent definitions must be updated before deletion."));
    }

    public MetricEntity createMetric(UUID datasetId, MetricRequest request) {
        MetricResponse response =
                createMetric(
                        new CreateMetricRequest(
                                datasetId,
                                request.datasetFieldId(),
                                request.name(),
                                request.displayName(),
                                Aggregation.valueOf(
                                        request.aggregation().toUpperCase(Locale.ROOT))));
        return metrics.getById(response.id());
    }

    public DimensionEntity createDimension(UUID datasetId, DimensionRequest request) {
        DimensionResponse response =
                createDimension(
                        new CreateDimensionRequest(
                                datasetId,
                                request.datasetFieldId(),
                                request.name(),
                                request.displayName(),
                                DimensionType.STANDARD,
                                Set.of()));
        return dimensions.getById(response.id());
    }

    private DatasetFieldEntity validateMetricField(
            UUID datasetId, UUID fieldId, Aggregation aggregation) {
        if (fieldId == null) {
            if (aggregation == Aggregation.COUNT) {
                return null;
            }
            throw new IllegalArgumentException(aggregation + " requires a field");
        }
        DatasetFieldEntity field = field(datasetId, fieldId);
        if (!field.isSelected() || !field.isMetricEnabled()) {
            throw new IllegalArgumentException("metric requires a selected metric-enabled field");
        }
        if ((aggregation == Aggregation.SUM || aggregation == Aggregation.AVG)
                && !NUMERIC_TYPES.contains(baseType(field.getStarrocksType()))) {
            throw new IllegalArgumentException(aggregation + " requires a numeric field");
        }
        return field;
    }

    private void validateDimensionField(
            UUID datasetId,
            UUID fieldId,
            DimensionType type,
            Set<TimeGranularity> allowedGranularities) {
        DatasetFieldEntity field = field(datasetId, fieldId);
        if (!field.isSelected() || !field.isDimensionEnabled()) {
            throw new IllegalArgumentException(
                    "dimension requires a selected dimension-enabled field");
        }
        if (type == DimensionType.TIME) {
            if (!TEMPORAL_TYPES.contains(baseType(field.getStarrocksType()))) {
                throw new IllegalArgumentException(
                        "TIME dimension requires a date or timestamp field");
            }
            if (allowedGranularities == null || allowedGranularities.isEmpty()) {
                throw new IllegalArgumentException(
                        "TIME dimension requires at least one granularity");
            }
        } else if (allowedGranularities != null && !allowedGranularities.isEmpty()) {
            throw new IllegalArgumentException(
                    "STANDARD dimension cannot declare time granularities");
        }
    }

    private void validateUniqueMetricName(UUID datasetId, String name, UUID excludedId) {
        MetricEntity existing =
                metrics.getOne(
                        new LambdaQueryWrapper<MetricEntity>()
                                .eq(MetricEntity::getDatasetId, datasetId)
                                .eq(MetricEntity::getName, name.trim()));
        if (existing != null && !existing.getId().equals(excludedId)) {
            throw new ConflictException("metric name already exists in dataset");
        }
    }

    private void validateUniqueDimension(
            UUID datasetId, UUID fieldId, String name, UUID excludedId) {
        for (DimensionEntity existing :
                dimensions.list(
                        new LambdaQueryWrapper<DimensionEntity>()
                                .eq(DimensionEntity::getDatasetId, datasetId))) {
            if (!existing.getId().equals(excludedId)
                    && (existing.getName().equals(name.trim())
                            || existing.getDatasetFieldId().equals(fieldId))) {
                throw new ConflictException("dimension name or field already exists in dataset");
            }
        }
    }

    private void replaceGranularities(UUID dimensionId, Set<TimeGranularity> values) {
        granularities.removeByDimensionId(dimensionId);
        if (values == null) {
            return;
        }
        for (TimeGranularity value : values) {
            DimensionTimeGranularityEntity entity = new DimensionTimeGranularityEntity();
            entity.setDimensionId(dimensionId);
            entity.setGranularity(value.name());
            granularities.saveGranularity(entity);
        }
    }

    private MetricResponse metricResponse(MetricEntity metric) {
        return new MetricResponse(
                metric.getId(),
                metric.getDatasetId(),
                metric.getDatasetFieldId(),
                metric.getName(),
                metric.getDisplayName(),
                Aggregation.valueOf(metric.getAggregation()),
                metric.getVersion(),
                metric.getDescription(),
                metric.getBusinessDefinition(),
                readAliases(metric.getAliases()),
                metric.getUnit(),
                metric.getFormatPattern(),
                metric.getStatus(),
                metric.getDomainName(),
                metric.getOwnerName(),
                metric.getCertificationStatus(),
                metric.getAggregationBehavior(),
                metric.getAdditivity(),
                metric.getMetricType(),
                metric.getDefaultTimeDimensionId());
    }

    private DimensionResponse dimensionResponse(DimensionEntity dimension) {
        Set<TimeGranularity> values = new HashSet<>();
        granularities
                .listByDimensionId(dimension.getId())
                .forEach(entity -> values.add(TimeGranularity.valueOf(entity.getGranularity())));
        return dimensionResponse(dimension, values);
    }

    private DimensionResponse dimensionResponse(
            DimensionEntity dimension, Set<TimeGranularity> values) {
        return new DimensionResponse(
                dimension.getId(),
                dimension.getDatasetId(),
                dimension.getDatasetFieldId(),
                dimension.getName(),
                dimension.getDisplayName(),
                DimensionType.valueOf(dimension.getDimensionType()),
                Set.copyOf(values),
                dimension.getVersion(),
                dimension.getDescription(),
                dimension.getBusinessDefinition(),
                readAliases(dimension.getAliases()),
                dimension.getDomainName(),
                dimension.getOwnerName(),
                dimension.getStatus(),
                dimension.getCertificationStatus(),
                dimension.getSemanticType(),
                dimension.getValueType(),
                dimension.getDefaultGranularity() == null
                        ? null
                        : TimeGranularity.valueOf(dimension.getDefaultGranularity()),
                dimension.getSensitivityLevel());
    }

    private void applyMetricMetadata(
            MetricEntity metric,
            String description,
            String businessDefinition,
            String unit,
            String formatPattern,
            String status,
            String domain,
            String owner,
            String certificationStatus,
            String aggregationBehavior,
            String additivity,
            String metricType,
            UUID defaultTimeDimensionId) {
        metric.setDescription(trimToNull(description));
        metric.setBusinessDefinition(trimToNull(businessDefinition));
        metric.setUnit(trimToNull(unit));
        metric.setFormatPattern(trimToNull(formatPattern));
        metric.setStatus(defaultValue(status, "ACTIVE"));
        metric.setDomainName(trimToNull(domain));
        metric.setOwnerName(trimToNull(owner));
        metric.setCertificationStatus(defaultValue(certificationStatus, "UNCERTIFIED"));
        metric.setAggregationBehavior(trimToNull(aggregationBehavior));
        metric.setAdditivity(trimToNull(additivity));
        metric.setMetricType(defaultValue(metricType, "BASE"));
        metric.setDefaultTimeDimensionId(defaultTimeDimensionId);
    }

    private void applyDimensionMetadata(
            DimensionEntity dimension,
            String description,
            String businessDefinition,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            String semanticType,
            String valueType,
            TimeGranularity defaultGranularity,
            String sensitivityLevel) {
        dimension.setDescription(trimToNull(description));
        dimension.setBusinessDefinition(trimToNull(businessDefinition));
        dimension.setDomainName(trimToNull(domain));
        dimension.setOwnerName(trimToNull(owner));
        dimension.setStatus(defaultValue(status, "ACTIVE"));
        dimension.setCertificationStatus(defaultValue(certificationStatus, "UNCERTIFIED"));
        dimension.setSemanticType(trimToNull(semanticType));
        dimension.setValueType(trimToNull(valueType));
        dimension.setDefaultGranularity(
                defaultGranularity == null ? null : defaultGranularity.name());
        dimension.setSensitivityLevel(defaultValue(sensitivityLevel, "INTERNAL"));
    }

    private String writeAliases(List<String> values) {
        List<String> normalized =
                values == null
                        ? List.of()
                        : values.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .map(String::trim)
                                .distinct()
                                .toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("aliases could not be serialized", exception);
        }
    }

    private List<String> readAliases(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored aliases are invalid", exception);
        }
    }

    private DatasetEntity dataset(UUID id) {
        return java.util.Optional.ofNullable(datasets.getById(id))
                .orElseThrow(() -> new NoSuchElementException("dataset not found: " + id));
    }

    private DatasetFieldEntity field(UUID datasetId, UUID id) {
        DatasetFieldEntity field =
                java.util.Optional.ofNullable(fields.getById(id))
                        .orElseThrow(
                                () -> new NoSuchElementException("dataset field not found: " + id));
        if (!datasetId.equals(field.getDatasetId())) {
            throw new IllegalArgumentException(
                    "field must belong to the semantic object's dataset");
        }
        return field;
    }

    private MetricEntity metric(UUID id) {
        return java.util.Optional.ofNullable(metrics.getById(id))
                .orElseThrow(() -> new NoSuchElementException("metric not found: " + id));
    }

    private DimensionEntity dimension(UUID id) {
        return java.util.Optional.ofNullable(dimensions.getById(id))
                .orElseThrow(() -> new NoSuchElementException("dimension not found: " + id));
    }

    private static String baseType(String type) {
        if (type == null) {
            return "";
        }
        int parameters = type.indexOf('(');
        return (parameters < 0 ? type : type.substring(0, parameters))
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean matches(String q, String... values) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("invalid page or size");
        }
    }

    private static <T> PageResponse<T> page(List<T> values, int page, int size) {
        int from = Math.min(values.size(), page * size);
        int to = Math.min(values.size(), from + size);
        return new PageResponse<>(values.subList(from, to), values.size(), page, size);
    }
}
