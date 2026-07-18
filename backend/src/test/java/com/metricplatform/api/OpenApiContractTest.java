package com.metricplatform.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {
    @Test
    void publishesEverySemanticResourceAndTheBreakingQueryShape() throws IOException {
        String contract = Files.readString(Path.of("../api/openapi.yaml"));
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(contract);
        assertThat(document.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.path("paths").size()).isEqualTo(38);
        assertThat(contract)
                .contains(
                        "/api/v1/datasets:",
                        "/api/v1/dataset-relationships:",
                        "/api/v1/metrics:",
                        "/api/v1/dimensions:",
                        "/api/v1/semantic-objects:search:",
                        "/api/v1/queries:validate:",
                        "/api/v1/queries:");
        String queryRequest =
                contract.substring(
                        contract.indexOf("    QueryRequest:"),
                        contract.indexOf("    QueryResponse:"));
        assertThat(queryRequest).contains("required: [metricIds]").doesNotContain("datasetId:");
        JsonNode querySchema = document.at("/components/schemas/QueryRequest");
        assertThat(querySchema.path("required").toString()).contains("\"metricIds\"");
        assertThat(querySchema.path("properties").has("datasetId")).isFalse();
    }
}
