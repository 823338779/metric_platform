package com.metricplatform.query;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private final DatasetMapper datasets;
    private final MetricMapper metrics;
    private final DimensionMapper dimensions;
    private final QueryService queries;

    public QueryController(
            DatasetMapper datasets,
            MetricMapper metrics,
            DimensionMapper dimensions,
            QueryService queries) {
        this.datasets = datasets;
        this.metrics = metrics;
        this.dimensions = dimensions;
        this.queries = queries;
    }

    @GetMapping("/datasets/{id}/semantic-model")
    public Object model(@PathVariable UUID id) {
        DatasetEntity dataset =
                java.util.Optional.ofNullable(datasets.selectById(id))
                        .orElseThrow(() -> new NoSuchElementException("dataset not found"));
        return java.util.Map.of(
                "dataset",
                dataset,
                "metrics",
                metrics.selectList(null).stream()
                        .filter(value -> id.equals(value.getDatasetId()))
                        .toList(),
                "dimensions",
                dimensions.selectList(null).stream()
                        .filter(value -> id.equals(value.getDatasetId()))
                        .toList());
    }

    @PostMapping("/semantic-objects:search")
    public List<QueryDtos.SemanticSearchResult> search(
            @Valid @RequestBody QueryDtos.SemanticSearchRequest request) {
        String needle = request.query().trim().toLowerCase(Locale.ROOT);
        Set<QueryDtos.SemanticObjectType> types =
                request.objectTypes().isEmpty()
                        ? Set.of(QueryDtos.SemanticObjectType.values())
                        : request.objectTypes();
        List<QueryDtos.SemanticSearchResult> results = new ArrayList<>();
        if (types.contains(QueryDtos.SemanticObjectType.DATASET)) {
            datasets.selectList(null).stream()
                    .filter(
                            value ->
                                    request.datasetIds().isEmpty()
                                            || request.datasetIds().contains(value.getId()))
                    .filter(value -> matches(needle, value.getName(), value.getDescription()))
                    .map(
                            value ->
                                    new QueryDtos.SemanticSearchResult(
                                            QueryDtos.SemanticObjectType.DATASET,
                                            value.getId(),
                                            value.getId(),
                                            value.getName(),
                                            value.getName()))
                    .forEach(results::add);
        }
        if (types.contains(QueryDtos.SemanticObjectType.METRIC)) {
            metrics.selectList(null).stream()
                    .filter(value -> allowed(request.datasetIds(), value.getDatasetId()))
                    .filter(value -> matches(needle, value.getName(), value.getDisplayName()))
                    .map(QueryController::result)
                    .forEach(results::add);
        }
        if (types.contains(QueryDtos.SemanticObjectType.DIMENSION)) {
            dimensions.selectList(null).stream()
                    .filter(value -> allowed(request.datasetIds(), value.getDatasetId()))
                    .filter(value -> matches(needle, value.getName(), value.getDisplayName()))
                    .map(QueryController::result)
                    .forEach(results::add);
        }
        return results;
    }

    @PostMapping("/queries:validate")
    public QueryDtos.ValidationResponse validate(
            @Valid @RequestBody QueryDtos.QueryRequest request) {
        return queries.validate(request);
    }

    @PostMapping("/queries")
    public QueryDtos.QueryResponse execute(@Valid @RequestBody QueryDtos.QueryRequest request)
            throws java.sql.SQLException {
        return queries.execute(request);
    }

    private static boolean allowed(Set<UUID> ids, UUID datasetId) {
        return ids.isEmpty() || ids.contains(datasetId);
    }

    private static boolean matches(String query, String... values) {
        if (query.isBlank()) return true;
        for (String value : values)
            if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }

    private static QueryDtos.SemanticSearchResult result(MetricEntity value) {
        return new QueryDtos.SemanticSearchResult(
                QueryDtos.SemanticObjectType.METRIC,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName());
    }

    private static QueryDtos.SemanticSearchResult result(DimensionEntity value) {
        return new QueryDtos.SemanticSearchResult(
                QueryDtos.SemanticObjectType.DIMENSION,
                value.getId(),
                value.getDatasetId(),
                value.getName(),
                value.getDisplayName());
    }
}
