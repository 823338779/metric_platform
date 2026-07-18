package com.metricplatform.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.DatasetRelationshipEntityPersistenceService;
import com.metricplatform.persistence.service.DimensionEntityPersistenceService;
import com.metricplatform.persistence.service.MetricEntityPersistenceService;
import com.metricplatform.query.QueryDtos.DimensionSelection;
import com.metricplatform.query.QueryDtos.QueryRequest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryDatasetResolverTest {
    @Mock DatasetEntityPersistenceService datasets;
    @Mock MetricEntityPersistenceService metrics;
    @Mock DimensionEntityPersistenceService dimensions;
    @Mock DatasetRelationshipEntityPersistenceService relationships;
    @InjectMocks QueryDatasetResolver resolver;

    @Test
    void resolvesReferencedDatasetsWithoutCallerPermissions() {
        UUID metricId = UUID.randomUUID();
        UUID dimensionId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        UUID datasetA = UUID.randomUUID();
        UUID datasetB = UUID.randomUUID();

        MetricEntity metric = new MetricEntity();
        metric.setId(metricId);
        metric.setDatasetId(datasetA);
        DimensionEntity dimension = new DimensionEntity();
        dimension.setId(dimensionId);
        dimension.setDatasetId(datasetA);
        DatasetRelationshipEntity relationship = new DatasetRelationshipEntity();
        relationship.setId(relationshipId);
        relationship.setSourceDatasetId(datasetA);
        relationship.setTargetDatasetId(datasetB);
        when(metrics.getById(metricId)).thenReturn(metric);
        when(dimensions.getById(dimensionId)).thenReturn(dimension);
        when(relationships.getById(relationshipId)).thenReturn(relationship);

        QueryRequest request =
                new QueryRequest(
                        List.of(metricId),
                        List.of(new DimensionSelection(dimensionId, null)),
                        Set.of(relationshipId));

        assertThat(resolver.datasetIds(request)).containsExactlyInAnyOrder(datasetA, datasetB);
    }

    @Test
    void rejectsMissingSemanticReferences() {
        UUID metricId = UUID.randomUUID();
        when(metrics.getById(metricId)).thenReturn(null);

        assertThatThrownBy(
                        () ->
                                resolver.datasetIds(
                                        new QueryRequest(List.of(metricId), List.of(), Set.of())))
                .isInstanceOf(NoSuchElementException.class);
    }
}
