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
        assertThat(document.path("paths").size()).isEqualTo(52);
        assertThat(
                        document.at(
                                        "/paths/~1api~1v1~1semantic-projects~1{projectId}~1queries/post/responses/202")
                                .isObject())
                .isTrue();
        assertThat(document.at("/paths/~1api~1v1~1semantic-projects/get/responses/200").isObject())
                .isTrue();
        assertThat(
                        document.at(
                                        "/paths/~1api~1v1~1semantic-projects~1{projectId}~1catalog/get/responses/410")
                                .isObject())
                .isTrue();
        assertThat(
                        document.at(
                                        "/paths/~1internal~1git-sync~1events/post/security/0/gitWebhookHmac")
                                .isArray())
                .isTrue();
        assertThat(document.at("/paths/~1api~1v1~1git-sync~1status/get/responses/200").isObject())
                .isTrue();
        assertThat(contract)
                .contains(
                        "/api/v1/datasets:",
                        "/api/v1/dataset-relationships:",
                        "/api/v1/metrics:",
                        "/api/v1/dimensions:",
                        "/api/v1/query-connectivity:",
                        "/api/v1/semantic-objects:search:",
                        "/api/v1/semantic-objects:query:",
                        "/api/v1/dimensions/{dimensionId}/members:query:",
                        "/api/v1/metrics/{id}/deletion-impact:",
                        "/api/v1/dimensions/{id}/deletion-impact:",
                        "/api/v1/dataset-relationships/{id}/deletion-impact:",
                        "/api/v1/queries:validate:",
                        "/api/v1/queries:");
        assertThat(document.at("/components/schemas/SemanticObjectQueryRequest").isObject())
                .isTrue();
        assertThat(document.at("/components/schemas/SemanticObjectQueryResponse").isObject())
                .isTrue();
        assertThat(document.at("/components/schemas/DimensionMemberQueryRequest").isObject())
                .isTrue();
        assertThat(document.at("/components/schemas/DimensionMemberQueryResponse").isObject())
                .isTrue();
        assertThat(document.at("/components/schemas/ResourceDeletionImpact").isObject()).isTrue();
        String queryRequest =
                contract.substring(
                        contract.indexOf("    QueryRequest:"),
                        contract.indexOf("    QueryResponse:"));
        assertThat(queryRequest).contains("required: [metricIds]").doesNotContain("datasetId:");
        JsonNode querySchema = document.at("/components/schemas/QueryRequest");
        assertThat(querySchema.path("required").toString()).contains("\"metricIds\"");
        assertThat(querySchema.path("properties").has("datasetId")).isFalse();
        assertThat(contract)
                .doesNotContain(
                        "/api/v1/admin/platform-users",
                        "x-required-roles",
                        "x-required-scopes",
                        "DatasetGrantRequest");
        assertThat(document.at("/components/schemas/IdentitySummary/required").toString())
                .isEqualTo("[\"identityId\",\"subject\",\"displayName\",\"authVersion\"]");
    }
}
