package com.metricplatform.semantic;

import static com.metricplatform.semantic.SemanticDtos.Aggregation.COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
}
