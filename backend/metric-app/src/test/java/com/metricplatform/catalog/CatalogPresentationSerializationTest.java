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
        when(gateway.resource(build, "metric.shop.units_sold"))
                .thenReturn(new ResourceView(build, "metric.shop.units_sold", details, null));
        var mvc = MockMvcBuilders.standaloneSetup(new PublishedCatalogController(gateway)).build();
        mvc.perform(get("/api/dbt/builds/" + build + "/resources/metric.shop.units_sold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.type").value("simple"))
                .andExpect(jsonPath("$.resource.type_params.measure").value("units_sold"))
                .andExpect(jsonPath("$.resource.enabled").value(true))
                .andExpect(jsonPath("$.resource.precision").value(2))
                .andExpect(jsonPath("$.resource.nodeType").doesNotExist());
    }
}
