package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.engine.EngineDtos.QueryJobRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 验证平台 HTTP 边界适配时间，同时保留查询身份和原请求。 */
class QueryTimeTransportTest {
    private static final String HOST = "127.0.0.1";
    private static final String HTTP = "http://";
    private static final String PROJECT = "sales";
    private static final String QUERY = "QUERY";
    private static final String EXPLAIN = "EXPLAIN";
    private static final String LEGACY_PATH = "/v1/query-jobs";
    private static final String PUBLISHED_PATH = "/v2/projects/sales/queries";
    private static final String COMPATIBILITY_PATH = "/v2/projects/sales/compatibility/queries";
    private static final String OPERATION = "queries";
    private static final String START = "startTime";
    private static final String END = "endTime";
    private static final String MODE = "mode";
    private static final String INPUT =
            """
            {"runId":"6f8d0b8f-0f5d-4582-91d5-79d6f76c3b38",
             "releaseId":"6e707522-73a4-468c-8446-ac4e2a5d68fd",
             "idempotencyKey":"8172018f-3cd7-403d-9228-16aacea17579",
             "metrics":["revenue","units_sold"],"groupBy":[],"filters":[],"limit":100}
            """;
    private static final String RESPONSE =
            """
            {"projectId":"sales","releaseId":"6e707522-73a4-468c-8446-ac4e2a5d68fd",
             "queryId":"7cb08be7-8399-484e-98f0-6915bfbff5cc","state":"QUEUED"}
            """;

    @ParameterizedTest
    @CsvSource({
        "2026-09-24T16:00:00.000Z,2026-10-01T06:08:00.000Z,2026-09-25T00:00:00,2026-10-01T14:08:00",
        "2026-09-25T00:00:00+08:00,2026-10-01T01:08:00-05:00,2026-09-25T00:00:00,2026-10-01T14:08:00",
        "2026-09-24T16:00:00,2026-10-01T06:08:00,2026-09-24T16:00:00,2026-10-01T06:08:00",
        "2026-09-24T16:00:00.123456Z,,2026-09-25T00:00:00.123456,",
        ",2026-10-01T06:08:00Z,,2026-10-01T14:08:00",
        ",,,",
        "invalid,invalid,invalid,invalid"
    })
    void normalizesTimeAcrossQueryTransportsWithoutMutatingInputs(
            String start, String end, String expectedStart, String expectedEnd) throws Exception {
        var mapper = new ObjectMapper();
        var received = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    received.set(mapper.readTree(exchange.getRequestBody()));
                    var bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(202, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        try {
            var uri = URI.create(HTTP + HOST + ":" + server.getAddress().getPort());
            var legacy = new HttpDbtRuntimeClient(uri, Duration.ofSeconds(2));
            var published = new HttpPublishedCatalogClient(uri, Duration.ofSeconds(2));
            // 旧版、新版与兼容查询均通过实际 HTTP 捕获报文，覆盖执行和 SQL 预览。
            for (String path : new String[] {LEGACY_PATH, PUBLISHED_PATH, COMPATIBILITY_PATH}) {
                for (String mode : new String[] {QUERY, EXPLAIN}) {
                    var body = (ObjectNode) mapper.readTree(INPUT);
                    body.put(MODE, mode).put(START, start).put(END, end);
                    if (LEGACY_PATH.equals(path)) {
                        body.remove("releaseId");
                        var request = mapper.treeToValue(body, QueryJobRequest.class);
                        legacy.submitQuery(request);
                        assertThat(request.startTime()).isEqualTo(start);
                        assertThat(request.endTime()).isEqualTo(end);
                        body = mapper.valueToTree(request);
                    } else if (PUBLISHED_PATH.equals(path)) {
                        published.submitQuery(PROJECT, body);
                    } else {
                        published.compatibility(PROJECT, OPERATION, body);
                    }
                    assertThat(body.path(START).textValue()).isEqualTo(start);
                    assertThat(body.path(END).textValue()).isEqualTo(end);
                    var expected = body.deepCopy();
                    expected.put(START, expectedStart).put(END, expectedEnd);
                    assertThat(received.get()).isEqualTo(expected);
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
