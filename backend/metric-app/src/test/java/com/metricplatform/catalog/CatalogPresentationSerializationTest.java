package com.metricplatform.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.catalog.BuildContracts.ResourceView;
import com.metricplatform.catalog.api.PublishedCatalogController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogPresentationSerializationTest {
    @Test
    void serializesArtifactValuesInsteadOfJacksonNodeIntrospectionProperties() throws Exception {
        var build = UUID.randomUUID();
        var gateway = mock(BuildGateway.class);
        var details =
                Map.<String, Object>of(
                        "type",
                        "simple",
                        "type_params",
                        Map.of("measure", "units_sold"),
                        "enabled",
                        true,
                        "precision",
                        2);
        when(gateway.resource(build, "metric.shop.units_sold", "native-details"))
                .thenReturn(
                        new ResourceView(
                                build,
                                "metric.shop.units_sold",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                details,
                                null,
                                null,
                                null,
                                null,
                                null));
        var mvc = MockMvcBuilders.standaloneSetup(new PublishedCatalogController(gateway)).build();
        mvc.perform(
                        get(
                                "/api/v3/dbt/builds/"
                                        + build
                                        + "/resources/metric.shop.units_sold/native-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeDetails.type").value("simple"))
                .andExpect(jsonPath("$.nativeDetails.type_params.measure").value("units_sold"))
                .andExpect(jsonPath("$.nativeDetails.enabled").value(true))
                .andExpect(jsonPath("$.nativeDetails.precision").value(2))
                .andExpect(jsonPath("$.nativeDetails.nodeType").doesNotExist());
    }
}
