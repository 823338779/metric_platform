package com.metricplatform.metadata;

import com.metricplatform.persistence.entity.*;
import com.metricplatform.persistence.mapper.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetadataService {
    private final DataSourceMapper sources;
    private final DatasetMapper datasets;
    private final DatasetFieldMapper fields;
    private final DimensionMapper dimensions;
    private final MetricMapper metrics;
    private final AuditEventMapper audit;

    @Transactional
    public DatasetEntity createDataset(MetadataDtos.CreateDatasetRequest r, Authentication auth) {
        if (sources.selectById(r.dataSourceId()) == null)
            throw new NoSuchElementException("data source not found");
        if (r.fields().stream()
                        .map(f -> f.physicalName().toLowerCase(Locale.ROOT))
                        .distinct()
                        .count()
                != r.fields().size())
            throw new IllegalArgumentException("physical field names must be unique");
        DatasetEntity d = new DatasetEntity();
        d.setId(UUID.randomUUID());
        d.setDataSourceId(r.dataSourceId());
        d.setName(r.name());
        d.setSchemaName(r.schemaName());
        d.setObjectName(r.objectName());
        datasets.insert(d);
        for (var request : r.fields()) {
            DatasetFieldEntity f = new DatasetFieldEntity();
            f.setId(UUID.randomUUID());
            f.setDatasetId(d.getId());
            f.setPhysicalName(request.physicalName());
            f.setDisplayName(request.displayName());
            f.setStarrocksType(request.starrocksType());
            f.setDimensionEnabled(request.dimensionEnabled());
            f.setMetricEnabled(request.metricEnabled());
            fields.insert(f);
        }
        audit(auth, "CREATE_DATASET", d.getId());
        return d;
    }

    public List<DatasetEntity> datasets() {
        return datasets.selectList(null);
    }

    private void audit(Authentication auth, String action, UUID resource) {
        AuditEventEntity e = new AuditEventEntity();
        e.setId(UUID.randomUUID());
        e.setActorId(
                auth != null && auth.getPrincipal() instanceof PlatformPrincipal p
                        ? p.id()
                        : "system");
        e.setAction(action);
        e.setResourceType("dataset");
        e.setResourceId(resource);
        audit.insert(e);
    }
}
