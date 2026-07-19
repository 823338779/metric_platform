package com.metricplatform.semantic;

import static com.metricplatform.semantic.SemanticDtos.Aggregation.COUNT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

class SemanticControllerTest {
    private final SemanticDefinitionService service = mock(SemanticDefinitionService.class);
    private final SemanticController controller = new SemanticController(service);

    @Test
    void createAndDeleteMetricUseResourceStatusCodes() {
        UUID datasetId = UUID.randomUUID();
        SemanticDtos.CreateMetricRequest request =
                new SemanticDtos.CreateMetricRequest(datasetId, null, "orders", "Orders", COUNT);
        SemanticDtos.MetricResponse response =
                new SemanticDtos.MetricResponse(
                        UUID.randomUUID(), datasetId, null, "orders", "Orders", COUNT, 0);
        when(service.createMetric(request)).thenReturn(response);

        assertThat(controller.createMetric(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.deleteMetric(response.id()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).deleteMetric(response.id());
    }

    @Test
    void exposesMetricAndDimensionDeletionImpactWithoutDeleting() {
        UUID metricId = UUID.randomUUID();
        UUID dimensionId = UUID.randomUUID();
        var metricImpact =
                new SemanticDtos.DeletionImpactResponse(metricId, true, List.of(), List.of());
        var dimensionImpact =
                new SemanticDtos.DeletionImpactResponse(
                        dimensionId,
                        false,
                        List.of(
                                new SemanticDtos.DependentResource(
                                        "METRIC",
                                        UUID.randomUUID(),
                                        "orders",
                                        "DEFAULT_TIME_DIMENSION")),
                        List.of());
        when(service.metricDeletionImpact(metricId)).thenReturn(metricImpact);
        when(service.dimensionDeletionImpact(dimensionId)).thenReturn(dimensionImpact);

        assertThat(controller.metricDeletionImpact(metricId)).isEqualTo(metricImpact);
        assertThat(controller.dimensionDeletionImpact(dimensionId)).isEqualTo(dimensionImpact);
        verify(service).metricDeletionImpact(metricId);
        verify(service).dimensionDeletionImpact(dimensionId);
    }
}
