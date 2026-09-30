package com.metricplatform.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.api.CatalogController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogPresentationSerializationTest {
    private static final String PROJECT = "shop";
    private static final String RESOURCE = "metric.shop.units_sold";
    private static final String PATH =
            "/api/v1/semantic-projects/{projectId}/resources/{resourceId}/presentation";
    private static final String DEFINITION =
            "{\"type\":\"simple\",\"type_params\":{\"measure\":\"units_sold\"},\"enabled\":true,\"precision\":2}";

    @Test
    void serializesArtifactValuesInsteadOfJacksonNodeIntrospectionProperties() throws Exception {
        var releaseId = UUID.randomUUID();
        var definition = new ObjectMapper().readTree(DEFINITION);
        var service = mock(PresentationService.class);
        when(service.present(PROJECT, releaseId, RESOURCE))
                .thenReturn(
                        new ResourcePresenter.ResourcePresentation(
                                releaseId,
                                RESOURCE,
                                "METRIC",
                                "units_sold",
                                List.of(
                                        new ResourcePresenter.Section(
                                                "指标",
                                                Map.of(
                                                        "type",
                                                        definition.get("type"),
                                                        "type_params",
                                                        definition.get("type_params")))),
                                definition,
                                true));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CatalogController(mock(CatalogReadRepository.class), service))
                        .build();
        mvc.perform(get(PATH, PROJECT, RESOURCE).param("releaseId", releaseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].fields.type").value("simple"))
                .andExpect(jsonPath("$.sections[0].fields.type_params.measure").value("units_sold"))
                .andExpect(jsonPath("$.nativeDetails.enabled").value(true))
                .andExpect(jsonPath("$.nativeDetails.precision").value(2))
                .andExpect(jsonPath("$.nativeDetails.nodeType").doesNotExist());
    }
}
