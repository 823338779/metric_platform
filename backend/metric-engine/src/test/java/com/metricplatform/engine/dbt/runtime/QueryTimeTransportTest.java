package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 平台保留时间精度和时区，具体日历解释由固定构建服务完成。 */
class QueryTimeTransportTest {
    @ParameterizedTest
    @CsvSource({
        "2026-09-24T16:00:00.000Z,2026-10-01T06:08:00.000Z",
        "2026-09-25T00:00:00+08:00,2026-10-01T01:08:00-05:00",
        "2026-09-24T16:00:00,2026-10-01T06:08:00",
        "2026-09-24T16:00:00.123456Z,",
        ",2026-10-01T06:08:00Z",
        ",",
        "invalid,invalid"
    })
    void preservesTimeAcrossQueryModesWithoutMutatingInputs(String start, String end)
            throws Exception {
        var mapper = new ObjectMapper();
        var received = new AtomicReference<JsonNode>();
        var path = new AtomicReference<String>();
        var build = UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    received.set(mapper.readTree(e.getRequestBody()));
                    path.set(e.getRequestURI().toString());
                    var bytes =
                            ("{\"buildId\":\""
                                            + build
                                            + "\",\"mode\":\"QUERY\",\"businessTimezone\":\"Asia/Shanghai\",\"normalizedTimeRange\":{},\"columns\":[],\"rows\":[]}")
                                    .getBytes(StandardCharsets.UTF_8);
                    var response =
                            (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(bytes);
                    response.put("mode", received.get().path("mode").asText());
                    if (received.get().path("mode").asText().equals("EXPLAIN")) {
                        response.put("sql", "select 1");
                        response.remove(List.of("columns", "rows"));
                    }
                    bytes = mapper.writeValueAsBytes(response);
                    e.sendResponseHeaders(200, bytes.length);
                    try (var out = e.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            var client =
                    new HttpBuildGateway(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            Duration.ofSeconds(2),
                            null);
            for (var mode :
                    List.of(QueryMode.QUERY, QueryMode.EXPLAIN, QueryMode.DIMENSION_VALUES)) {
                var request =
                        new QueryRequest(
                                mode,
                                List.of("revenue"),
                                List.of(),
                                List.of(),
                                start,
                                end,
                                List.of(),
                                100,
                                null,
                                null);
                client.query(build, request);
                assertThat(received.get()).isEqualTo(mapper.valueToTree(request));
                assertThat(received.get().has("buildId")).isFalse();
                assertThat(received.get().has("idempotencyKey")).isFalse();
                assertThat(path.get()).isEqualTo("/query?build_id=" + build);
                assertThat(request.startTime()).isEqualTo(start);
                assertThat(request.endTime()).isEqualTo(end);
            }
        } finally {
            server.stop(0);
        }
    }
}
