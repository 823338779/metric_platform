package com.metricplatform.query;

import static com.metricplatform.query.QueryDtos.ConnectivityPathStatus.AMBIGUOUS_PATH;
import static com.metricplatform.query.QueryDtos.ConnectivityPathStatus.UNIQUE_PATH;
import static com.metricplatform.query.planner.DatasetRelationshipGraph.Cardinality.MANY_TO_ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.query.QueryDtos.ConnectivityPath;
import com.metricplatform.query.QueryDtos.DatasetConnectivity;
import com.metricplatform.query.QueryDtos.QueryConnectivityResponse;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryConnectivityServiceTest {
    private static final UUID SOURCE_A = id(1);
    private static final UUID SOURCE_B = id(2);
    private static final UUID ORDER = id(11);
    private static final UUID CUSTOMER = id(12);
    private static final UUID REGION = id(13);
    private static final UUID ALTERNATE = id(14);
    private static final UUID OTHER = id(15);
    private static final UUID ORDER_CUSTOMER = id(21);
    private static final UUID CUSTOMER_REGION = id(22);
    private static final UUID ORDER_ALTERNATE = id(23);
    private static final UUID ALTERNATE_REGION = id(24);

    @Mock DatasetEntityPersistenceService datasets;
    @Mock SemanticCatalogReader catalog;
    @Mock SemanticCatalogService semanticCatalog;
    @InjectMocks QueryConnectivityService service;

    @Test
    void returnsDatasetSourcesAndDirectedUniquePaths() {
        when(datasets.list()).thenReturn(List.of(dataset(ORDER, SOURCE_A), dataset(CUSTOMER, SOURCE_A)));
        when(catalog.relationships()).thenReturn(List.of(relationship(ORDER_CUSTOMER, ORDER, CUSTOMER)));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-7");

        QueryConnectivityResponse response = service.connectivity();

        assertThat(response.catalogVersion()).isEqualTo("catalog-7");
        assertThat(response.datasets())
                .extracting(DatasetConnectivity::datasetId, DatasetConnectivity::dataSourceId)
                .containsExactlyInAnyOrder(tuple(ORDER, SOURCE_A), tuple(CUSTOMER, SOURCE_A));
        assertThat(response.paths())
                .containsExactly(new ConnectivityPath(
                        ORDER, CUSTOMER, UNIQUE_PATH, List.of(ORDER_CUSTOMER)));
    }

    @Test
    void omitsMissingReverseAndCrossSourcePaths() {
        when(datasets.list()).thenReturn(List.of(
                dataset(ORDER, SOURCE_A), dataset(CUSTOMER, SOURCE_A), dataset(OTHER, SOURCE_B)));
        when(catalog.relationships()).thenReturn(List.of(relationship(ORDER_CUSTOMER, ORDER, CUSTOMER)));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-7");

        assertThat(service.connectivity().paths())
                .noneMatch(path -> path.sourceDatasetId().equals(CUSTOMER)
                        && path.targetDatasetId().equals(ORDER))
                .noneMatch(path -> path.sourceDatasetId().equals(OTHER)
                        || path.targetDatasetId().equals(OTHER));
    }

    @Test
    void reportsAmbiguousPathsWithoutSelectingOne() {
        when(datasets.list()).thenReturn(List.of(
                dataset(ORDER, SOURCE_A), dataset(CUSTOMER, SOURCE_A),
                dataset(ALTERNATE, SOURCE_A), dataset(REGION, SOURCE_A)));
        when(catalog.relationships()).thenReturn(List.of(
                relationship(ORDER_CUSTOMER, ORDER, CUSTOMER),
                relationship(CUSTOMER_REGION, CUSTOMER, REGION),
                relationship(ORDER_ALTERNATE, ORDER, ALTERNATE),
                relationship(ALTERNATE_REGION, ALTERNATE, REGION)));
        when(semanticCatalog.catalogVersion()).thenReturn("catalog-8");

        ConnectivityPath path = service.connectivity().paths().stream()
                .filter(value -> value.sourceDatasetId().equals(ORDER)
                        && value.targetDatasetId().equals(REGION))
                .findFirst()
                .orElseThrow();

        assertThat(path.status()).isEqualTo(AMBIGUOUS_PATH);
        assertThat(path.relationshipIds()).isEmpty();
    }

    private static DatasetEntity dataset(UUID datasetId, UUID dataSourceId) {
        DatasetEntity entity = new DatasetEntity();
        entity.setId(datasetId);
        entity.setDataSourceId(dataSourceId);
        return entity;
    }

    private static RelationshipDefinition relationship(UUID relationshipId, UUID source, UUID target) {
        return new RelationshipDefinition(relationshipId, source, target, MANY_TO_ONE, List.of());
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
