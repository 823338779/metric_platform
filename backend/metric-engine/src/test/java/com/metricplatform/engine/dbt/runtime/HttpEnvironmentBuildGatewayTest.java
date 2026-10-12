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
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v4/builds",
                exchange -> {
                    seen.add(exchange.getRequestMethod());
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer private-token");
                    byte[] body =
                            ("{\"buildId\":\""
                                            + build
                                            + "\",\"execution\":{\"environmentId\":\""
                                            + environment
                                            + "\"}}")
                                    .getBytes(StandardCharsets.UTF_8);
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
            assertThat(seen).containsExactly("POST", "POST");
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
