package com.metricplatform.authorization;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.entity.PlatformIdentityDatasetGrantEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import com.metricplatform.persistence.service.PlatformIdentityDatasetGrantPersistenceService;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.security.PlatformPrincipal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class MetricAuthorizationService {
    private static final String ACCESS_DENIED = "Access denied";

    private final PlatformIdentityDatasetGrantPersistenceService grants;
    private final DatasetEntityPersistenceService datasets;
    private final MetricEntityPersistenceService metrics;
    private final DimensionEntityPersistenceService dimensions;
    private final DatasetRelationshipEntityPersistenceService relationships;

    public MetricAuthorizationService(
            PlatformIdentityDatasetGrantPersistenceService grants,
            DatasetEntityPersistenceService datasets,
            MetricEntityPersistenceService metrics,
            DimensionEntityPersistenceService dimensions,
            DatasetRelationshipEntityPersistenceService relationships) {
        this.grants = grants;
        this.datasets = datasets;
        this.metrics = metrics;
        this.dimensions = dimensions;
        this.relationships = relationships;
    }

    public Set<UUID> discoverableDatasetIds(PlatformPrincipal principal) {
        requirePrincipal(principal);
        return grantedDatasetIds(principal, true);
    }

    public void requireDiscover(PlatformPrincipal principal, UUID datasetId) {
        if (!discoverableDatasetIds(principal).contains(datasetId)
                || datasets.getById(datasetId) == null) {
            deny();
        }
    }

    public void requireResolve(
            PlatformPrincipal principal, QueryDtos.SemanticResolveRequest request) {
        Set<UUID> allowed = discoverableDatasetIds(principal);
        for (QueryDtos.SemanticObjectReference reference : request.objects()) {
            requireAllowed(allowed, datasetId(reference));
        }
        for (UUID relationshipId : request.relationshipIds()) {
            DatasetRelationshipEntity relationship = relationships.getById(relationshipId);
            if (relationship == null
                    || !allowed.contains(relationship.getSourceDatasetId())
                    || !allowed.contains(relationship.getTargetDatasetId())) {
                deny();
            }
        }
    }

    public void requireResolve(
            PlatformPrincipal principal, Collection<QueryDtos.SemanticObjectReference> references) {
        Set<UUID> allowed = discoverableDatasetIds(principal);
        for (QueryDtos.SemanticObjectReference reference : references) {
            requireAllowed(allowed, datasetId(reference));
        }
    }

    public void requireMemberSearch(PlatformPrincipal principal, UUID dimensionId) {
        Set<UUID> allowed = discoverableDatasetIds(principal);
        DimensionEntity dimension = dimensions.getById(dimensionId);
        requireAllowed(allowed, dimension == null ? null : dimension.getDatasetId());
    }

    public void requireQuery(
            PlatformPrincipal principal,
            QueryDtos.QueryRequest request,
            QueryPermission permission) {
        authorizeQuery(principal, request, permission);
    }

    public Set<UUID> authorizeQuery(
            PlatformPrincipal principal,
            QueryDtos.QueryRequest request,
            QueryPermission permission) {
        requirePrincipal(principal);
        if (permission == null) {
            deny();
        }
        Set<UUID> referencedDatasets = queryDatasetIds(request);
        Set<UUID> allowed = grantedDatasetIds(principal, false);
        if (referencedDatasets.isEmpty() || !allowed.containsAll(referencedDatasets)) {
            deny();
        }
        return referencedDatasets;
    }

    private Set<UUID> queryDatasetIds(QueryDtos.QueryRequest request) {
        Set<UUID> datasetIds = new HashSet<>();
        for (UUID metricId : request.metricIds()) {
            MetricEntity metric = metrics.getById(metricId);
            addRequired(datasetIds, metric == null ? null : metric.getDatasetId());
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
                MetricEntity metric = metrics.getById(sort.objectId());
                addRequired(datasetIds, metric == null ? null : metric.getDatasetId());
            } else {
                addDimensionDataset(datasetIds, sort.objectId());
            }
        }
        for (UUID relationshipId : request.relationshipIds()) {
            DatasetRelationshipEntity relationship = relationships.getById(relationshipId);
            if (relationship == null) {
                deny();
            }
            addRequired(datasetIds, relationship.getSourceDatasetId());
            addRequired(datasetIds, relationship.getTargetDatasetId());
        }
        return Set.copyOf(datasetIds);
    }

    private void addDimensionDataset(Set<UUID> datasetIds, UUID dimensionId) {
        DimensionEntity dimension = dimensions.getById(dimensionId);
        addRequired(datasetIds, dimension == null ? null : dimension.getDatasetId());
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

    private Set<UUID> grantedDatasetIds(PlatformPrincipal principal, boolean discover) {
        LambdaQueryWrapper<PlatformIdentityDatasetGrantEntity> query =
                new LambdaQueryWrapper<PlatformIdentityDatasetGrantEntity>()
                        .eq(PlatformIdentityDatasetGrantEntity::getTenantId, principal.tenantId())
                        .eq(
                                PlatformIdentityDatasetGrantEntity::getIdentityId,
                                principal.identityId());
        if (discover) {
            query.eq(PlatformIdentityDatasetGrantEntity::isDiscoverAllowed, true);
        } else {
            query.eq(PlatformIdentityDatasetGrantEntity::isQueryAllowed, true);
        }
        return grants.list(query).stream()
                .map(PlatformIdentityDatasetGrantEntity::getDatasetId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void requireAllowed(Set<UUID> allowed, UUID datasetId) {
        if (datasetId == null || !allowed.contains(datasetId)) {
            deny();
        }
    }

    private static void addRequired(Set<UUID> datasetIds, UUID datasetId) {
        if (datasetId == null) {
            deny();
        }
        datasetIds.add(datasetId);
    }

    private static void requirePrincipal(PlatformPrincipal principal) {
        if (principal == null || principal.identityId() == null || principal.tenantId() == null) {
            deny();
        }
    }

    private static void deny() {
        throw new AccessDeniedException(ACCESS_DENIED);
    }
}
