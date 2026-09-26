package com.metricplatform.dbt;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DbtOpenApiContractTest {
    private static final String ROOT = "/api/v1/semantic-projects/{projectId}";

    @Test
    void openApiIncludesVersionedPresentationAndMetricFlowRoutes() throws Exception {
        var api =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Files.readString(Path.of("..", "api", "openapi.yaml")));
        var paths = api.path("paths");
        assertThat(paths.has(ROOT + "/resources/{resourceId}/presentation")).isTrue();
        assertThat(paths.has(ROOT + "/resources/{resourceId}/source")).isTrue();
        assertThat(paths.has(ROOT + "/query-options")).isTrue();
        assertThat(paths.has(ROOT + "/queries:dimension-values")).isTrue();
        assertThat(
                        api.path("components")
                                .path("schemas")
                                .path("DbtQueryStatus")
                                .path("properties")
                                .path("mode")
                                .path("enum")
                                .toString())
                .contains("DIMENSION_VALUES");
    }
}
