package com.metricplatform.query;

import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class QueryDatasetResolver {
    private final DatasetEntityPersistenceService datasets;
    private final MetricEntityPersistenceService metrics;
    private final DimensionEntityPersistenceService dimensions;
    private final DatasetRelationshipEntityPersistenceService relationships;

    public QueryDatasetResolver(
            DatasetEntityPersistenceService datasets,
            MetricEntityPersistenceService metrics,
            DimensionEntityPersistenceService dimensions,
            DatasetRelationshipEntityPersistenceService relationships) {
        this.datasets = datasets;
        this.metrics = metrics;
        this.dimensions = dimensions;
        this.relationships = relationships;
    }

    public void requireResolve(QueryDtos.SemanticResolveRequest request) {
        for (QueryDtos.SemanticObjectReference reference : request.objects()) {
            required(datasetId(reference));
        }
        for (UUID relationshipId : request.relationshipIds()) {
            required(relationships.getById(relationshipId));
        }
    }

    public Set<UUID> datasetIds(QueryDtos.QueryRequest request) {
        Set<UUID> datasetIds = new HashSet<>();
        for (UUID metricId : request.metricIds()) {
            MetricEntity metric = required(metrics.getById(metricId));
            datasetIds.add(required(metric.getDatasetId()));
        }
        for (QueryDtos.DimensionSelection selection : request.dimensions()) {
            addDimensionDataset(datasetIds, selection.dimensionId());
        }
        for (QueryDtos.DimensionFilter filter : request.filters()) {
            addDimensionDataset(datasetIds, filter.dimensionId());
        }
        if (request.timeRange() != null) {
            addDimensionDataset(datasetIds, request.timeRange().dimensionId());
        }
        for (QueryDtos.SortSelection sort : request.sort()) {
            if (sort.type() == QueryDtos.SortTargetType.METRIC) {
                MetricEntity metric = required(metrics.getById(sort.objectId()));
                datasetIds.add(required(metric.getDatasetId()));
            } else {
                addDimensionDataset(datasetIds, sort.objectId());
            }
        }
        for (UUID relationshipId : request.relationshipIds()) {
            DatasetRelationshipEntity relationship = required(relationships.getById(relationshipId));
            datasetIds.add(required(relationship.getSourceDatasetId()));
            datasetIds.add(required(relationship.getTargetDatasetId()));
        }
        return Set.copyOf(datasetIds);
    }

    private void addDimensionDataset(Set<UUID> datasetIds, UUID dimensionId) {
        DimensionEntity dimension = required(dimensions.getById(dimensionId));
        datasetIds.add(required(dimension.getDatasetId()));
    }

    private UUID datasetId(QueryDtos.SemanticObjectReference reference) {
        return switch (reference.type()) {
            case DATASET -> datasets.getById(reference.id()) == null ? null : reference.id();
            case METRIC -> {
                MetricEntity metric = metrics.getById(reference.id());
                yield metric == null ? null : metric.getDatasetId();
            }
            case DIMENSION -> {
                DimensionEntity dimension = dimensions.getById(reference.id());
                yield dimension == null ? null : dimension.getDatasetId();
            }
        };
    }

    private static <T> T required(T value) {
        if (value == null) throw new NoSuchElementException("Semantic object not found");
        return value;
    }
}
