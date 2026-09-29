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
    void publishesSemanticPlatformAndAuthenticationRoutes() throws IOException {
        String contract = Files.readString(Path.of("..", "..", "api", "openapi.yaml"));
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(contract);
        assertThat(document.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.path("paths").size()).isEqualTo(17);
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
        assertThat(document.path("paths").has("/api/v1/metrics")).isFalse();
        assertThat(document.path("paths").has("/api/v1/dimensions")).isFalse();
        assertThat(document.path("paths").has("/api/v1/datasets")).isFalse();
        assertThat(document.path("paths").has("/api/v1/queries")).isFalse();
        assertThat(document.path("paths").has("/api/v1/data-sources")).isFalse();
        assertThat(document.path("paths").has("/api/v1/git-sync/status")).isFalse();
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
