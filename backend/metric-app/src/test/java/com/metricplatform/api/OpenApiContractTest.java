package com.metricplatform.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {
    @Test
    void publishesScopedOntologyReadsAndMutations() throws Exception {
        var api =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Files.readString(Path.of("..", "..", "api", "openapi.yaml")));
        String base = "/api/dbt/environments/{environmentId}/ontology";
        var paths = api.path("paths");
        assertThat(paths.path(base).path("get").path("parameters").toString()).contains("refresh");
        assertThat(
                        api.at("/components/schemas/OntologyObjectWrite/properties")
                                .has("acceptPhysicalTypes"))
                .isTrue();
        assertThat(api.at("/components/schemas/OntologyFinding/properties").has("sourceEvidence"))
                .isTrue();
        for (String suffix : List.of("", "/datasets", "/datasets/{assetId}/columns"))
            assertThat(paths.path(base + suffix).path("get").path("responses").has("200"))
                    .as(suffix)
                    .isTrue();
        for (String suffix : List.of("/objects", "/relations")) {
            assertThat(paths.path(base + suffix).path("post").path("responses").has("201"))
                    .isTrue();
            var item = paths.path(base + suffix + "/{id}");
            for (String method : List.of("put", "delete")) {
                assertThat(item.path(method).path("responses").has("409")).isTrue();
                assertThat(item.path(method).has("security")).isTrue();
            }
        }
    }

    @Test
    void publishesSemanticPlatformAndAuthenticationRoutes() throws Exception {
        var api =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Files.readString(Path.of("..", "..", "api", "openapi.yaml")));
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("x-protocol-version").asText()).isEqualTo("execution-snapshot");
        assertThat(api.at("/components/schemas/NativeCatalog/type").asText()).isEqualTo("array");
        for (var path :
                List.of(
                        "/api/v1/auth/token",
                        "/api/v1/auth/me",
                        "/api/dbt/builds",
                        "/api/dbt/repository/branches",
                        "/api/dbt/builds/{buildId}",
                        "/api/dbt/deployments",
                        "/api/dbt/deployments/current",
                        "/api/dbt/builds/{buildId}/catalog",
                        "/api/dbt/builds/{buildId}/resources/{resourceId}",
                        "/api/dbt/builds/{buildId}/lineage",
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
                .isEqualTo(
                        "[\"identityId\",\"subject\",\"displayName\",\"authVersion\",\"tenant_id\",\"role\"]");
    }
}
