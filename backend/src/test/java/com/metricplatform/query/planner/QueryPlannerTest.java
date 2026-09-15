package com.metricplatform.query.planner;

import static com.metricplatform.semantic.SemanticDtos.TimeGranularity.MONTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.query.SemanticCatalogReader;
import com.metricplatform.semantic.SemanticDtos;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class QueryPlannerTest {
    private final SemanticCatalogReader catalog = mock(SemanticCatalogReader.class);
    private final UUID orderDataset = UUID.randomUUID();
    private final UUID ticketDataset = UUID.randomUUID();
    private final UUID regionDataset = UUID.randomUUID();
    private final UUID salesMetric = UUID.randomUUID();
    private final UUID ticketMetric = UUID.randomUUID();
    private final UUID regionDimension = UUID.randomUUID();

    @Test
    void readsMetadataInReadOnlyRepeatableReadAndReleasesTransactionAfterPlanning()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation())
                .thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(catalog.metric(salesMetric))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .getCurrentTransactionIsolationLevel())
                                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                            return metric(salesMetric, orderDataset);
                        });
        when(catalog.relationships())
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return List.of();
                        });
        ProxyFactory factory = new ProxyFactory(new QueryPlanner(catalog));
        factory.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(dataSource),
                        new AnnotationTransactionAttributeSource()));
        QueryPlanner planner = (QueryPlanner) factory.getProxy();

        QueryPlan plan =
                planner.plan(new QueryDtos.QueryRequest(List.of(salesMetric), List.of(), Set.of()));

        assertThat(plan.metrics()).hasSize(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void createsOneAggregatePerMetricDatasetInRequestOrder() {
        when(catalog.metric(salesMetric)).thenReturn(metric(salesMetric, orderDataset));
        when(catalog.metric(ticketMetric)).thenReturn(metric(ticketMetric, ticketDataset));
        when(catalog.dimension(regionDimension))
                .thenReturn(dimension(regionDimension, regionDataset, "STANDARD", Set.of()));
        UUID orderRegion = UUID.randomUUID();
        UUID ticketRegion = UUID.randomUUID();
        when(catalog.relationships())
                .thenReturn(
                        List.of(
                                relationship(orderRegion, orderDataset, regionDataset),
                                relationship(ticketRegion, ticketDataset, regionDataset)));

        QueryPlan plan =
                new QueryPlanner(catalog)
                        .plan(
                                new QueryDtos.QueryRequest(
                                        List.of(salesMetric, ticketMetric),
                                        List.of(
                                                new QueryDtos.DimensionSelection(
                                                        regionDimension, null)),
                                        Set.of()));

        assertThat(plan.aggregates())
                .extracting(QueryPlan.DatasetAggregatePlan::datasetId)
                .containsExactly(orderDataset, ticketDataset);
        assertThat(plan.aggregates())
                .allSatisfy(aggregate -> assertThat(aggregate.paths()).hasSize(1));
    }

    @Test
    void rejectsGranularityOnAStandardDimension() {
        when(catalog.metric(salesMetric)).thenReturn(metric(salesMetric, orderDataset));
        when(catalog.dimension(regionDimension))
                .thenReturn(dimension(regionDimension, regionDataset, "STANDARD", Set.of()));
        when(catalog.relationships())
                .thenReturn(List.of(relationship(UUID.randomUUID(), orderDataset, regionDataset)));

        assertThatThrownBy(
                        () ->
                                new QueryPlanner(catalog)
                                        .plan(
                                                new QueryDtos.QueryRequest(
                                                        List.of(salesMetric),
                                                        List.of(
                                                                new QueryDtos.DimensionSelection(
                                                                        regionDimension, MONTH)),
                                                        Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STANDARD");
    }

    private static SemanticCatalogReader.MetricDefinition metric(UUID id, UUID datasetId) {
        MetricEntity metric = new MetricEntity();
        metric.setId(id);
        metric.setDatasetId(datasetId);
        metric.setName("metric_" + id);
        metric.setAggregation("COUNT");
        return new SemanticCatalogReader.MetricDefinition(metric, dataset(datasetId), null);
    }

    private static SemanticCatalogReader.DimensionDefinition dimension(
            UUID id, UUID datasetId, String type, Set<SemanticDtos.TimeGranularity> allowed) {
        DimensionEntity dimension = new DimensionEntity();
        dimension.setId(id);
        dimension.setDatasetId(datasetId);
        dimension.setDatasetFieldId(UUID.randomUUID());
        dimension.setName("dimension_" + id);
        dimension.setDimensionType(type);
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setId(dimension.getDatasetFieldId());
        field.setDatasetId(datasetId);
        field.setPhysicalName("id");
        return new SemanticCatalogReader.DimensionDefinition(
                dimension, dataset(datasetId), field, allowed);
    }

    private static SemanticCatalogReader.RelationshipDefinition relationship(
            UUID id, UUID sourceId, UUID targetId) {
        return new SemanticCatalogReader.RelationshipDefinition(
                id,
                sourceId,
                targetId,
                DatasetRelationshipGraph.Cardinality.MANY_TO_ONE,
                List.of());
    }

    private static DatasetEntity dataset(UUID id) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(id);
        dataset.setDataSourceId(UUID.fromString("70000000-0000-0000-0000-000000000001"));
        return dataset;
    }
}
