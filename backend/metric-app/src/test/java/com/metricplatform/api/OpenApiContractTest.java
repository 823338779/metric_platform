package com.metricplatform.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class OpenApiContractTest {
    @Test
    void publishesSemanticPlatformAndAuthenticationRoutes() throws Exception {
        var api =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Files.readString(Path.of("..", "..", "api", "openapi.yaml")));
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("x-protocol-version").asText()).isEqualTo("execution-snapshot");
        for (var path :
                java.util.List.of(
                        "/api/v1/auth/token",
                        "/api/v1/auth/me",
                        "/api/dbt/builds",
                        "/api/dbt/builds/{buildId}",
                        "/api/dbt/deployments",
                        "/api/dbt/deployments/current",
                        "/api/dbt/builds/{buildId}/catalog",
                        "/api/dbt/builds/{buildId}/resources/{resourceId}",
                        "/api/dbt/builds/{buildId}/resources/{resourceId}/{view}",
                        "/api/dbt/builds/{buildId}/available-dimensions",
                        "/api/dbt/builds/{buildId}/queries",
                        "/api/dbt/contract"))
            assertThat(api.path("paths").has(path)).as(path).isTrue();
        api.path("paths")
                .fieldNames()
                .forEachRemaining(
                        path ->
                                assertThat(path)
                                        .doesNotContain(
                                                "semantic-projects", "git-sync", "/projects"));
        assertThat(api.at("/paths/~1api~1v3~1dbt~1builds").has("post")).isFalse();
        assertThat(api.at("/paths/~1api~1v3~1dbt~1deployments").has("post")).isFalse();
        assertThat(api.at("/components/schemas/IdentitySummary/required").toString())
                .isEqualTo("[\"identityId\",\"subject\",\"displayName\",\"authVersion\"]");
    }
}
