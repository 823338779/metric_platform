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
        assertThat(document.path("paths").size()).isEqualTo(44);
        // 保留原 18 个路由的约束，并逐条验证本次新增的 12 个代理路由。
        String proxyRoot = "/api/v2/semantic-projects";
        for (String suffix :
                java.util.List.of(
                        "",
                        "/{projectId}/publication",
                        "/{projectId}/releases",
                        "/{projectId}/releases/{releaseId}",
                        "/{projectId}/releases/{releaseId}/catalog",
                        "/{projectId}/releases/{releaseId}/resources/{resourceId}",
                        "/{projectId}/releases/{releaseId}/resources/{resourceId}/lineage",
                        "/{projectId}/releases/{releaseId}/resources/{resourceId}/source",
                        "/{projectId}/releases/{releaseId}/resources/{resourceId}/native-details",
                        "/{projectId}/query-options",
                        "/{projectId}/queries",
                        "/{projectId}/queries/{queryId}")) {
            assertThat(document.path("paths").has(proxyRoot + suffix)).isTrue();
        }
        // 保留原有 30 个路径断言，精确追加分支只读与查询契约。
        String branchRoot = proxyRoot + "/{projectId}/branches";
        for (String suffix :
                java.util.List.of(
                        "",
                        "/{branchId}",
                        "/{branchId}/publication",
                        "/{branchId}/releases",
                        "/{branchId}/releases/{releaseId}",
                        "/{branchId}/releases/{releaseId}/catalog",
                        "/{branchId}/releases/{releaseId}/diff",
                        "/{branchId}/releases/{releaseId}/resources/{resourceId}",
                        "/{branchId}/releases/{releaseId}/resources/{resourceId}/lineage",
                        "/{branchId}/releases/{releaseId}/resources/{resourceId}/source",
                        "/{branchId}/releases/{releaseId}/resources/{resourceId}/native-details",
                        "/{branchId}/query-options",
                        "/{branchId}/queries",
                        "/{branchId}/queries/{queryId}")) {
            assertThat(document.path("paths").has(branchRoot + suffix)).isTrue();
        }
        assertThat(document.path("paths").path(branchRoot).has("post")).isFalse();
        assertThat(document.path("paths").path(branchRoot + "/{branchId}").has("delete")).isFalse();
        assertThat(
                        document.at(
                                        "/paths/~1api~1v1~1semantic-projects:refresh/post/responses/202")
                                .isObject())
                .isTrue();
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
        var webhookSecurity = document.at("/paths/~1internal~1git-sync~1events/post/security");
        assertThat(webhookSecurity.isArray()).isTrue();
        assertThat(webhookSecurity).isEmpty();
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
