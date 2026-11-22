package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpEnvironmentBuildGatewayTest {
    @Test
    void accepts202WithServiceAuthenticationAndRejectsMismatchedEnvironment() throws Exception {
        UUID environment = UUID.randomUUID(), build = UUID.randomUUID();
        var seen = new java.util.ArrayList<String>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var reply = json.createObjectNode();
        reply.put("buildId", build.toString());
        reply.put("repository", request(environment).repository());
        reply.put("branchName", request(environment).branchName());
        reply.put("buildStatus", "QUEUED");
        reply.set("execution", json.valueToTree(request(environment).execution().forBuild()));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/builds",
                exchange -> {
                    seen.add(exchange.getRequestMethod());
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer private-token");
                    var submitted = json.readTree(exchange.getRequestBody());
                    assertThat(submitted.required("execution").has("configVersion")).isFalse();
                    byte[] body = reply.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(202, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var gateway =
                    new HttpEnvironmentBuildGateway(
                            new HttpBuildGateway(
                                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                    Duration.ofSeconds(2),
                                    "private-token"));
            var request = request(environment);
            assertThat(gateway.submit(request).path("buildId").asText())
                    .isEqualTo(build.toString());
            assertThatThrownBy(() -> gateway.submit(request(UUID.randomUUID())))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> assertThat(failure.status()).isEqualTo(502));
            // 同一环境身份下 schema 不符也不能将回执当作原构建。
            ((com.fasterxml.jackson.databind.node.ObjectNode) reply.path("execution"))
                    .put("schema", "other_schema");
            assertThatThrownBy(() -> gateway.submit(request))
                    .isInstanceOf(BuildGateway.Failure.class);
            reply.set("execution", json.valueToTree(request.execution().forBuild()));
            reply.put("repository", "https://example.test/other.git");
            assertThatThrownBy(() -> gateway.submit(request))
                    .isInstanceOf(BuildGateway.Failure.class);
            assertThat(seen).containsExactly("POST", "POST", "POST", "POST");
        } finally {
            server.stop(0);
        }
    }

    private SnapshotBuildRequest request(UUID environment) {
        return new SnapshotBuildRequest(
                "https://example.test/repo.git",
                "feature/test",
                null,
                new ExecutionSnapshot(
                        environment,
                        7,
                        new ConnectionTarget("starrocks", "warehouse", "localhost", 9030, null),
                        "test",
                        "default_catalog",
                        "test_schema",
                        2,
                        "UTC"),
                "NONE",
                UUID.randomUUID().toString());
    }
}
