package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 当前上游协议的真实 HTTP 边界回归，避免旧 DTO 与桩同时过时。 */
class LatestServiceContractTest {
    private static final UUID BUILD = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final String METRIC = "revenue";
    private static final String EXPLAIN_RESPONSE =
            "{\"buildId\":\""
                    + BUILD
                    + "\",\"mode\":\"EXPLAIN\",\"commitSha\":null,\"businessTimezone\":\"UTC\","
                    + "\"normalizedTimeRange\":{\"startTime\":null,\"endTime\":null},\"sql\":\"select 1\"}";
    private static final String LOG_RESPONSE =
            "{\"items\":[],\"nextCursor\":\"tail\",\"hasMore\":false}";

    @Test
    void explainAcceptsSqlWithoutTabularFieldsAndUsesMetricNames() throws Exception {
        var body = new AtomicReference<String>();
        var server = server(EXPLAIN_RESPONSE, body, new AtomicReference<>());
        try {
            var result =
                    client(server)
                            .query(
                                    BUILD,
                                    new QueryRequest(
                                            QueryMode.EXPLAIN,
                                            List.of(METRIC),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            100,
                                            null,
                                            null));
            assertThat(result.sql()).isEqualTo("select 1");
            var sent = new ObjectMapper().readTree(body.get());
            assertThat(sent.path("metrics").get(0).asText()).isEqualTo(METRIC);
            assertThat(sent.has("metricResourceIds")).isFalse();
            assertThat(result.rows()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void logsReadStructuredExecutionEndpointAndPreserveTailCursor() throws Exception {
        var path = new AtomicReference<String>();
        var server = server(LOG_RESPONSE, new AtomicReference<>(), path);
        try {
            var logs = new HttpEnvironmentBuildGateway(client(server)).logs(BUILD, "tail", 100);
            assertThat(path.get()).startsWith("/builds/" + BUILD + "/execution-logs?");
            assertThat(logs.path("nextCursor").asText()).isEqualTo("tail");
            assertThat(logs.path("hasMore").asBoolean()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeCatalogKeepsUnknownFieldsWithoutInventingPagination() throws Exception {
        var path = new AtomicReference<String>();
        var server =
                server(
                        "[{\"unique_id\":\"metric.shop.revenue\",\"resource_type\":\"metric\",\"extension\":{\"enabled\":true}}]",
                        new AtomicReference<>(),
                        path);
        try {
            var manifest = client(server).catalog(BUILD);
            assertThat(path.get()).isEqualTo("/builds/" + BUILD + "/catalog");
            assertThat(manifest.get(0).path("extension").path("enabled").asBoolean()).isTrue();
            assertThat(manifest.isArray()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeCatalogAcceptsEmptyListAndRejectsOldObject() throws Exception {
        var empty = server("[]", new AtomicReference<>(), new AtomicReference<>());
        var old = server("{}", new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThat(client(empty).catalog(BUILD).isEmpty()).isTrue();
            assertThatThrownBy(() -> client(old).catalog(BUILD))
                    .isInstanceOf(BuildGateway.Failure.class);
        } finally {
            empty.stop(0);
            old.stop(0);
        }
    }

    @Test
    void nativeCatalogCanExceedTheQueryResultLimit() throws Exception {
        String description = "x".repeat(8 * 1024 * 1024);
        var server =
                server(
                        "[{\"unique_id\":\"model.shop.orders\",\"resource_type\":\"model\",\"description\":\""
                                + description
                                + "\"}]",
                        new AtomicReference<>(),
                        new AtomicReference<>());
        try {
            assertThat(client(server).catalog(BUILD).get(0).path("description").asText())
                    .isEqualTo(description);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lineageEncodesSelectorPlusSignsAndPreservesNativeNodes() throws Exception {
        var path = new AtomicReference<String>();
        var server =
                server(
                        "[{\"unique_id\":\"model.shop.orders\",\"depends_on\":{\"nodes\":[]}}]",
                        new AtomicReference<>(),
                        path);
        try {
            var nodes = client(server).lineage(BUILD, "1+orders+1");
            assertThat(path.get())
                    .isEqualTo("/builds/" + BUILD + "/lineage?selector=1%2Borders%2B1");
            assertThat(nodes.get(0).path("unique_id").asText()).isEqualTo("model.shop.orders");
        } finally {
            server.stop(0);
        }
    }

    private static HttpBuildGateway client(HttpServer server) {
        return new HttpBuildGateway(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2),
                null);
    }

    private static HttpServer server(
            String response, AtomicReference<String> body, AtomicReference<String> path)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    path.set(exchange.getRequestURI().toString());
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        return server;
    }
}
