package com.metricplatform.query;

import static com.metricplatform.query.QueryDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionTimeGranularityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import com.metricplatform.semantic.SemanticDtos.TimeGranularity;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SemanticCatalogService {
    private static final String API_VERSION = "v1";
    private static final int DATABASE_CANDIDATE_LIMIT = 500;

    private final DatasetEntityPersistenceService datasets;
    private final MetricEntityPersistenceService metrics;
    private final DimensionEntityPersistenceService dimensions;
    private final ObjectMapper objectMapper;
    private final DimensionTimeGranularityPersistenceService granularities;
    private final DatasetRelationshipEntityPersistenceService relationships;
    private final SemanticObjectCursorCodec cursors;

    public SemanticCatalogService(
            DatasetEntityPersistenceService datasets,
            MetricEntityPersistenceService metrics,
            DimensionEntityPersistenceService dimensions,
            ObjectMapper objectMapper,
            DimensionTimeGranularityPersistenceService granularities,
            DatasetRelationshipEntityPersistenceService relationships,
            SemanticObjectCursorCodec cursors) {
        this.datasets = datasets;
        this.metrics = metrics;
        this.dimensions = dimensions;
        this.objectMapper = objectMapper;
        this.granularities = granularities;
        this.relationships = relationships;
        this.cursors = cursors;
    }

    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                API_VERSION,
                catalogVersion(),
                Set.of(
                        "SEMANTIC_SEARCH",
                        "SEMANTIC_RESOLVE",
                        "MEMBER_SEARCH",
                        "RELATIONSHIP_PLANNING",
                        "VALIDATE",
                        "EXECUTE",
                        "FILTERS",
                        "TIME_RANGE",
                        "SORT",
                        "LIMIT"),
                Map.of(
                        "maxMetrics",
                        50,
                        "maxDimensions",
                        50,
                        "maxRows",
                        1000,
                        "maxSearchResults",
                        100,
                        "maxMemberResults",
                        100),
                Set.of(TimePreset.values()),
                false);
    }

    public List<SemanticSearchResult> search(SemanticSearchRequest request) {
        return search(request, null);
    }

    public List<SemanticSearchResult> search(
            SemanticSearchRequest request, Set<UUID> discoverableDatasetIds) {
        return collect(request, discoverableDatasetIds).stream()
                .sorted(
                        Comparator.comparingDouble(SemanticSearchResult::score)
                                .reversed()
                                .thenComparing(SemanticSearchResult::name)
                                .thenComparing(SemanticSearchResult::id))
                .limit(request.topK())
                .toList();
    }

    public SemanticObjectQueryResponse query(
            SemanticObjectQueryRequest request, Set<UUID> discoverableDatasetIds) {
        SemanticSearchRequest legacy =
                new SemanticSearchRequest(
                        request.text() == null ? "" : request.text(),
                        request.datasetIds(),
                        request.types(),
                        request.domains(),
                        request.statuses(),
                        request.certifiedOnly(),
                        100);
        List<SemanticSearchResult> filtered = collect(legacy, discoverableDatasetIds);
        Comparator<SemanticSearchResult> comparator = comparator(request.sort());
        List<SemanticSearchResult> ordered = filtered.stream().sorted(comparator).toList();
        String catalogVersion = catalogVersion();
        int start = cursorStart(request, ordered, catalogVersion);
        int pageEnd = Math.min(ordered.size(), start + request.limit());
        boolean hasMore = pageEnd < ordered.size();
        List<SemanticSearchResult> items = List.copyOf(ordered.subList(start, pageEnd));
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            SemanticSearchResult last = items.getLast();
            nextCursor =
                    cursors.encode(
                            new SemanticObjectCursorCodec.Cursor(
                                    catalogVersion,
                                    request.sort(),
                                    cursorKey(last, request.sort()),
                                    last.id()));
        }
        return new SemanticObjectQueryResponse(
                items,
                new PageInfo(nextCursor, hasMore, request.limit()),
                facets(filtered),
                catalogVersion);
    }

    private List<SemanticSearchResult> collect(
            SemanticSearchRequest request, Set<UUID> discoverableDatasetIds) {
        Set<UUID> authorizedDatasetIds = authorizedDatasetIds(request, discoverableDatasetIds);
        if (authorizedDatasetIds != null && authorizedDatasetIds.isEmpty()) {
            return List.of();
        }
        String needle = normalize(request.query());
        Set<SemanticObjectType> types =
                request.objectTypes().isEmpty()
                        ? Set.of(SemanticObjectType.values())
                        : request.objectTypes();
        List<SemanticSearchResult> results = new ArrayList<>();
        if (types.contains(SemanticObjectType.DATASET)) {
            datasetCandidates(request, needle, authorizedDatasetIds).stream()
                    .map(value -> datasetResult(value, needle))
                    .filter(value -> needle.isBlank() || value.score() > 0)
                    .forEach(results::add);
        }
        if (types.contains(SemanticObjectType.METRIC)) {
            metricCandidates(request, needle, authorizedDatasetIds).stream()
                    .map(value -> metricResult(value, needle))
                    .filter(value -> needle.isBlank() || value.score() > 0)
                    .forEach(results::add);
        }
        if (types.contains(SemanticObjectType.DIMENSION)) {
            dimensionCandidates(request, needle, authorizedDatasetIds).stream()
                    .map(value -> dimensionResult(value, needle))
                    .filter(value -> needle.isBlank() || value.score() > 0)
                    .forEach(results::add);
        }
        return List.copyOf(results);
    }

    private int cursorStart(
            SemanticObjectQueryRequest request,
            List<SemanticSearchResult> ordered,
            String catalogVersion) {
        if (request.cursor() == null) return 0;
        SemanticObjectCursorCodec.Cursor cursor =
                cursors.decode(request.cursor(), catalogVersion, request.sort());
        for (int index = 0; index < ordered.size(); index++) {
            SemanticSearchResult candidate = ordered.get(index);
            if (candidate.id().equals(cursor.lastId())
                    && cursorKey(candidate, request.sort()).equals(cursor.lastKey())) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("semantic object cursor is stale or forged");
    }

    private static Comparator<SemanticSearchResult> comparator(SemanticObjectSort sort) {
        Comparator<SemanticSearchResult> id = Comparator.comparing(SemanticSearchResult::id);
        return switch (sort) {
            case RELEVANCE_DESC ->
                    Comparator.comparingDouble(SemanticSearchResult::score)
                            .reversed()
                            .thenComparing(value -> normalized(value.displayName()))
                            .thenComparing(id);
            case NAME_ASC ->
                    Comparator.comparing(
                                    (SemanticSearchResult value) -> normalized(value.displayName()))
                            .thenComparing(id);
            case UPDATED_AT_DESC ->
                    Comparator.comparing(
                                    (SemanticSearchResult value) ->
                                            value.updatedAt() == null
                                                    ? Instant.EPOCH
                                                    : value.updatedAt())
                            .reversed()
                            .thenComparing(id);
        };
    }

    private static String cursorKey(SemanticSearchResult value, SemanticObjectSort sort) {
        return switch (sort) {
            case RELEVANCE_DESC ->
                    String.format(
                            Locale.ROOT,
                            "%.12f|%s",
                            value.score(),
                            normalized(value.displayName()));
            case NAME_ASC -> normalized(value.displayName());
            case UPDATED_AT_DESC ->
                    Long.toString(
                            (value.updatedAt() == null ? Instant.EPOCH : value.updatedAt())
                                    .toEpochMilli());
        };
    }

    private static SemanticObjectFacets facets(List<SemanticSearchResult> values) {
        Map<SemanticObjectType, Long> types =
                values.stream()
                        .collect(
                                Collectors.groupingBy(
                                        SemanticSearchResult::type,
                                        () -> new java.util.EnumMap<>(SemanticObjectType.class),
                                        Collectors.counting()));
        return new SemanticObjectFacets(
                Map.copyOf(types),
                stringFacet(values, SemanticSearchResult::domain),
                stringFacet(values, SemanticSearchResult::status));
    }

    private static Map<String, Long> stringFacet(
            List<SemanticSearchResult> values, Function<SemanticSearchResult, String> extractor) {
        return values.stream()
                .map(extractor)
                .filter(value -> value != null && !value.isBlank())
                .collect(
                        Collectors.groupingBy(
                                Function.identity(), TreeMap::new, Collectors.counting()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public SemanticResolveResponse resolve(SemanticResolveRequest request) {
        return resolve(request, null);
    }

    public SemanticResolveResponse resolve(
            SemanticResolveRequest request, Set<UUID> discoverableDatasetIds) {
        List<ResolvedSemanticObject> resolved =
                request.objects().stream().map(this::resolveOne).toList();
        Set<UUID> datasetIds = new HashSet<>();
        resolved.forEach(value -> datasetIds.add(value.datasetId()));
        List<RelationshipPathSummary> paths =
                relationships.list().stream()
                        .filter(value -> "ACTIVE".equals(defaultValue(value.getStatus(), "ACTIVE")))
                        .filter(
                                value ->
                                        request.relationshipIds().isEmpty()
                                                || request.relationshipIds()
                                                        .contains(value.getId()))
                        .filter(
                                value ->
                                        datasetIds.contains(value.getSourceDatasetId())
                                                || datasetIds.contains(value.getTargetDatasetId()))
                        .filter(
                                value ->
                                        discoverableDatasetIds == null
                                                || (discoverableDatasetIds.contains(
                                                                value.getSourceDatasetId())
                                                        && discoverableDatasetIds.contains(
                                                                value.getTargetDatasetId())))
                        .map(SemanticCatalogService::path)
                        .toList();
        return new SemanticResolveResponse(catalogVersion(), resolved, paths);
    }

    public Object semanticModel(UUID id) {
        DatasetEntity dataset = required(datasets.getById(id), "dataset", id);
        return Map.of(
                "dataset", resolveDataset(dataset),
                "metrics",
                        metrics
                                .list(
                                        new LambdaQueryWrapper<MetricEntity>()
                                                .eq(MetricEntity::getDatasetId, id))
                                .stream()
                                .map(value -> resolveMetric(value, dataset))
                                .toList(),
                "dimensions",
                        dimensions
                                .list(
                                        new LambdaQueryWrapper<DimensionEntity>()
                                                .eq(DimensionEntity::getDatasetId, id))
                                .stream()
                                .map(value -> resolveDimension(value, dataset))
                                .toList());
    }

    public String catalogVersion() {
        Instant latest = Instant.EPOCH;
        for (DatasetEntity value : datasets.list()) latest = later(latest, value.getUpdatedAt());
        for (MetricEntity value : metrics.list()) latest = later(latest, value.getUpdatedAt());
        for (DimensionEntity value : dimensions.list())
            latest = later(latest, value.getUpdatedAt());
        return "catalog-" + latest.toEpochMilli();
    }

    private List<DatasetEntity> datasetCandidates(
            SemanticSearchRequest request, String needle, Set<UUID> authorizedDatasetIds) {
        LambdaQueryWrapper<DatasetEntity> query = new LambdaQueryWrapper<>();
        if (authorizedDatasetIds != null) query.in(DatasetEntity::getId, authorizedDatasetIds);
        if (!request.domains().isEmpty()) query.in(DatasetEntity::getDomainName, request.domains());
        if (!request.statuses().isEmpty()) query.in(DatasetEntity::getStatus, request.statuses());
        if (request.certifiedOnly()) query.eq(DatasetEntity::getCertificationStatus, "CERTIFIED");
        if (!needle.isBlank()) {
            query.and(
                    value ->
                            value.like(DatasetEntity::getName, needle)
                                    .or()
                                    .like(DatasetEntity::getDescription, needle)
                                    .or()
                                    .like(DatasetEntity::getBusinessGrain, needle)
                                    .or()
                                    .like(DatasetEntity::getAliases, needle));
        }
        return datasets.list(query.last("LIMIT " + DATABASE_CANDIDATE_LIMIT));
    }

    private List<MetricEntity> metricCandidates(
            SemanticSearchRequest request, String needle, Set<UUID> authorizedDatasetIds) {
        LambdaQueryWrapper<MetricEntity> query = new LambdaQueryWrapper<>();
        if (authorizedDatasetIds != null)
            query.in(MetricEntity::getDatasetId, authorizedDatasetIds);
        if (!request.domains().isEmpty()) query.in(MetricEntity::getDomainName, request.domains());
        if (!request.statuses().isEmpty()) query.in(MetricEntity::getStatus, request.statuses());
        if (request.certifiedOnly()) query.eq(MetricEntity::getCertificationStatus, "CERTIFIED");
        if (!needle.isBlank()) {
            query.and(
                    value ->
                            value.like(MetricEntity::getName, needle)
                                    .or()
                                    .like(MetricEntity::getDisplayName, needle)
                                    .or()
                                    .like(MetricEntity::getDescription, needle)
                                    .or()
                                    .like(MetricEntity::getBusinessDefinition, needle)
                                    .or()
                                    .like(MetricEntity::getAliases, needle));
        }
        return metrics.list(query.last("LIMIT " + DATABASE_CANDIDATE_LIMIT));
    }

    private List<DimensionEntity> dimensionCandidates(
            SemanticSearchRequest request, String needle, Set<UUID> authorizedDatasetIds) {
        LambdaQueryWrapper<DimensionEntity> query = new LambdaQueryWrapper<>();
        if (authorizedDatasetIds != null)
            query.in(DimensionEntity::getDatasetId, authorizedDatasetIds);
        if (!request.domains().isEmpty())
            query.in(DimensionEntity::getDomainName, request.domains());
        if (!request.statuses().isEmpty()) query.in(DimensionEntity::getStatus, request.statuses());
        if (request.certifiedOnly()) query.eq(DimensionEntity::getCertificationStatus, "CERTIFIED");
        if (!needle.isBlank()) {
            query.and(
                    value ->
                            value.like(DimensionEntity::getName, needle)
                                    .or()
                                    .like(DimensionEntity::getDisplayName, needle)
                                    .or()
                                    .like(DimensionEntity::getDescription, needle)
                                    .or()
                                    .like(DimensionEntity::getBusinessDefinition, needle)
                                    .or()
                                    .like(DimensionEntity::getAliases, needle));
        }
        return dimensions.list(query.last("LIMIT " + DATABASE_CANDIDATE_LIMIT));
    }

    private SemanticSearchResult datasetResult(DatasetEntity value, String needle) {
        List<String> objectAliases = readAliases(value.getAliases());
        Match match =
                match(
                        needle,
                        value.getName(),
                        value.getName(),
                        objectAliases,
                        value.getDescription(),
                        value.getBusinessGrain());
        return new SemanticSearchResult(
                SemanticObjectType.DATASET,
                value.getId(),
                value.getId(),
                value.getName(),
                value.getName(),
                value.getDescription(),
                objectAliases,
                match.score(),
                match.reasons(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                value.getDomainName(),
                value.getVersion(),
                value.getUpdatedAt(),
                Map.of(
                        "businessGrain",
                        safe(value.getBusinessGrain()),
                        "qualityStatus",
                        safe(value.getQualityStatus())));
    }

    private SemanticSearchResult metricResult(MetricEntity value, String needle) {
        List<String> objectAliases = readAliases(value.getAliases());
        Match match =
                match(
                        needle,
                        value.getName(),
                        value.getDisplayName(),
                        objectAliases,
                        value.getDescription(),
                        value.getBusinessDefinition());
        return new SemanticSearchResult(
                SemanticObjectType.METRIC,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName(),
                value.getDescription(),
                objectAliases,
                match.score(),
                match.reasons(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                value.getDomainName(),
                value.getVersion(),
                value.getUpdatedAt(),
                Map.of(
                        "aggregation",
                        safe(value.getAggregation()),
                        "unit",
                        safe(value.getUnit()),
                        "metricType",
                        safe(value.getMetricType())));
    }

    private SemanticSearchResult dimensionResult(DimensionEntity value, String needle) {
        List<String> objectAliases = readAliases(value.getAliases());
        Match match =
                match(
                        needle,
                        value.getName(),
                        value.getDisplayName(),
                        objectAliases,
                        value.getDescription(),
                        value.getBusinessDefinition());
        return new SemanticSearchResult(
                SemanticObjectType.DIMENSION,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName(),
                value.getDescription(),
                objectAliases,
                match.score(),
                match.reasons(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                value.getDomainName(),
                value.getVersion(),
                value.getUpdatedAt(),
                Map.of(
                        "dimensionType",
                        safe(value.getDimensionType()),
                        "semanticType",
                        safe(value.getSemanticType()),
                        "valueType",
                        safe(value.getValueType())));
    }

    private ResolvedSemanticObject resolveOne(SemanticObjectReference reference) {
        return switch (reference.type()) {
            case DATASET ->
                    resolveDataset(
                            required(datasets.getById(reference.id()), "dataset", reference.id()));
            case METRIC -> {
                MetricEntity metric =
                        required(metrics.getById(reference.id()), "metric", reference.id());
                yield resolveMetric(
                        metric,
                        required(
                                datasets.getById(metric.getDatasetId()),
                                "dataset",
                                metric.getDatasetId()));
            }
            case DIMENSION -> {
                DimensionEntity dimension =
                        required(dimensions.getById(reference.id()), "dimension", reference.id());
                yield resolveDimension(
                        dimension,
                        required(
                                datasets.getById(dimension.getDatasetId()),
                                "dataset",
                                dimension.getDatasetId()));
            }
        };
    }

    private ResolvedSemanticObject resolveDataset(DatasetEntity value) {
        return new ResolvedSemanticObject(
                SemanticObjectType.DATASET,
                value.getId(),
                value.getId(),
                value.getName(),
                value.getName(),
                value.getDescription(),
                value.getBusinessGrain(),
                readAliases(value.getAliases()),
                value.getDomainName(),
                value.getOwnerName(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                value.getBusinessGrain(),
                null,
                null,
                null,
                Set.of(),
                value.getFreshnessPolicy(),
                value.getQualityStatus(),
                value.getVersion());
    }

    private ResolvedSemanticObject resolveMetric(MetricEntity value, DatasetEntity dataset) {
        return new ResolvedSemanticObject(
                SemanticObjectType.METRIC,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName(),
                value.getDescription(),
                value.getBusinessDefinition(),
                readAliases(value.getAliases()),
                value.getDomainName(),
                value.getOwnerName(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                dataset.getBusinessGrain(),
                value.getUnit(),
                value.getFormatPattern(),
                null,
                Set.of(),
                dataset.getFreshnessPolicy(),
                dataset.getQualityStatus(),
                value.getVersion());
    }

    private ResolvedSemanticObject resolveDimension(DimensionEntity value, DatasetEntity dataset) {
        Set<TimeGranularity> allowed =
                granularities.listByDimensionId(value.getId()).stream()
                        .map(item -> TimeGranularity.valueOf(item.getGranularity()))
                        .collect(java.util.stream.Collectors.toSet());
        return new ResolvedSemanticObject(
                SemanticObjectType.DIMENSION,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName(),
                value.getDescription(),
                value.getBusinessDefinition(),
                readAliases(value.getAliases()),
                value.getDomainName(),
                value.getOwnerName(),
                defaultValue(value.getStatus(), "ACTIVE"),
                defaultValue(value.getCertificationStatus(), "UNCERTIFIED"),
                dataset.getBusinessGrain(),
                null,
                null,
                value.getValueType(),
                Set.copyOf(allowed),
                dataset.getFreshnessPolicy(),
                dataset.getQualityStatus(),
                value.getVersion());
    }

    private List<String> readAliases(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored aliases are invalid", exception);
        }
    }

    private static RelationshipPathSummary path(DatasetRelationshipEntity value) {
        return new RelationshipPathSummary(
                value.getId(),
                value.getName(),
                value.getDisplayPath(),
                value.getSourceDatasetId(),
                value.getTargetDatasetId(),
                value.getCardinality(),
                value.getSemanticRole(),
                value.isFanoutSafe());
    }

    private static Match match(
            String needle,
            String name,
            String displayName,
            List<String> aliases,
            String description,
            String businessDefinition) {
        if (needle.isBlank()) return new Match(1, List.of("all"));
        if (normalize(name).equals(needle)) return new Match(100, List.of("exact_name"));
        if (aliases.stream().map(SemanticCatalogService::normalize).anyMatch(needle::equals))
            return new Match(95, List.of("exact_alias"));
        if (normalize(displayName).equals(needle))
            return new Match(90, List.of("exact_display_name"));
        if (normalize(name).startsWith(needle) || normalize(displayName).startsWith(needle))
            return new Match(75, List.of("name_prefix"));
        if (aliases.stream()
                .map(SemanticCatalogService::normalize)
                .anyMatch(value -> value.startsWith(needle)))
            return new Match(70, List.of("alias_prefix"));
        if (normalize(name).contains(needle) || normalize(displayName).contains(needle))
            return new Match(60, List.of("name_contains"));
        if (aliases.stream()
                .map(SemanticCatalogService::normalize)
                .anyMatch(value -> value.contains(needle)))
            return new Match(55, List.of("alias_contains"));
        if (normalize(description).contains(needle)
                || normalize(businessDefinition).contains(needle))
            return new Match(35, List.of("description_contains"));
        return new Match(0, List.of());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Instant later(Instant left, Instant right) {
        return right != null && right.isAfter(left) ? right : left;
    }

    private static Set<UUID> authorizedDatasetIds(
            SemanticSearchRequest request, Set<UUID> discoverableDatasetIds) {
        if (discoverableDatasetIds == null) {
            return request.datasetIds().isEmpty() ? null : request.datasetIds();
        }
        Set<UUID> authorized = new HashSet<>(discoverableDatasetIds);
        if (!request.datasetIds().isEmpty()) {
            authorized.retainAll(request.datasetIds());
        }
        return Set.copyOf(authorized);
    }

    private static <T> T required(T value, String type, UUID id) {
        if (value == null) throw new NoSuchElementException(type + " not found: " + id);
        return value;
    }

    private record Match(double score, List<String> reasons) {}
}
