package com.metricplatform.contract;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class SemanticOpenApiContractTest {
    @Test
    void openApiIncludesVersionedPresentationAndMetricFlowRoutes() throws Exception {
        var api =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Files.readString(Path.of("..", "..", "api", "openapi.yaml")));
        var schemas = api.at("/components/schemas");
        for (var route :
                java.util.List.of(
                        "/api/dbt/builds/{buildId}/available-dimensions",
                        "/api/dbt/builds/{buildId}/queries")) {
            var responses = api.path("paths").path(route).path("post").path("responses");
            assertThat(responses.has("200")).isTrue();
            assertThat(responses.has("202")).isFalse();
        }
        assertThat(api.path("paths").has("/api/dbt/query-options/{optionsTaskId}")).isFalse();
        assertThat(api.path("paths").has("/api/dbt/queries/{queryId}/status")).isFalse();
        assertThat(
                        schemas.path("AvailableDimensionsRequest")
                                .path("properties")
                                .has("idempotencyKey"))
                .isFalse();
        assertThat(schemas.path("QueryMode").path("enum").toString())
                .contains("QUERY", "EXPLAIN", "PREVIEW", "DIMENSION_VALUES");
        assertThat(schemas.path("QueryRequest").path("properties").has("buildId")).isFalse();
        assertThat(schemas.path("QueryRequest").path("properties").has("releaseId")).isFalse();
        assertThat(schemas.path("ResultPage").path("oneOf")).hasSize(3);
        assertThat(schemas.path("ExplainResult").path("properties").has("sql")).isTrue();
        assertThat(schemas.path("ExplainResult").path("properties").has("rows")).isFalse();
        assertThat(schemas.path("PreviewRequest").path("properties").has("selector")).isTrue();
        assertThat(schemas.path("PreviewRequest").path("properties").has("metrics")).isFalse();
        assertThat(schemas.path("AvailableDimensionsRequest").path("properties").has("metrics"))
                .isTrue();
        assertThat(schemas.path("ResultPage").path("properties").has("nextOffset")).isFalse();
        assertThat(schemas.path("AvailableDimensionsView").path("properties").has("dimensions"))
                .isTrue();
    }
}
