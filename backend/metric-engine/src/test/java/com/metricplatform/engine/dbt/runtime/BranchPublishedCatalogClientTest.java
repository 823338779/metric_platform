package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.PublishedCatalogGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证分支路由与响应归属，无分支兼容请求始终保留生产路径。 */
class BranchPublishedCatalogClientTest {
    @Test
    void forwardsFixedBranchAndKeepsLegacyProductionPath() throws Exception {
        var branch = UUID.randomUUID();
        var release = UUID.randomUUID();
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    paths.add(exchange.getRequestURI().getPath());
                    var bytes =
                            ("{\"projectId\":\"p\",\"branchId\":\""
                                            + branch
                                            + "\",\"activePublication\":null}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            var client =
                    new HttpPublishedCatalogClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            Duration.ofSeconds(2));
            assertThat(client.publication("p", branch).path("branchId").asText())
                    .isEqualTo(branch.toString());
            client.publication("p");
            assertThat(paths)
                    .containsExactly(
                            "/v2/projects/p/branches/" + branch + "/publication",
                            "/v2/projects/p/publication");
            assertThatThrownBy(() -> client.publication("p", UUID.randomUUID()))
                    .isInstanceOfSatisfying(
                            PublishedCatalogGateway.Failure.class,
                            failure -> assertThat(failure.status()).isEqualTo(502));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesBranchFailuresWithoutTurningThemIntoAnEmptyCatalog() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var code = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/",
                exchange -> {
                    var bytes =
                            "{\"detail\":\"private connection data\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code.get(), bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            var client =
                    new HttpPublishedCatalogClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            Duration.ofSeconds(2));
            for (int status : new int[] {401, 404, 409, 410, 503}) {
                code.set(status);
                assertThatThrownBy(
                                () ->
                                        client.catalog(
                                                "p",
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                "",
                                                "",
                                                1,
                                                50))
                        .isInstanceOfSatisfying(
                                PublishedCatalogGateway.Failure.class,
                                failure -> {
                                    assertThat(failure.status()).isEqualTo(status);
                                    assertThat(failure.getMessage()).doesNotContain("private");
                                });
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void branchProtocolRequiresIdentityAndRef() throws Exception {
        var json = new ObjectMapper();
        assertThatThrownBy(() -> PublishedProtocol.branch(json.readTree("{\"projectId\":\"p\"}")))
                .isInstanceOf(PublishedCatalogGateway.Failure.class);
    }
}
