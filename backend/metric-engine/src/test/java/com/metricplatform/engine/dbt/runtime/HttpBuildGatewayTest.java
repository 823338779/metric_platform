package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;

class HttpBuildGatewayTest {
    private static final UUID BUILD = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void v3FailureNeverFallsBack() throws Exception {
        var paths = new ArrayList<String>();
        var server =
                server(
                        paths,
                        410,
                        "{\"code\":\"PHYSICAL_OBJECTS_REMOVED\",\"message\":\"removed\",\"retryable\":false,\"phase\":\"QUERY\",\"buildId\":\""
                                + BUILD
                                + "\"}");
        try {
            assertThatThrownBy(() -> client(server).getBuild(BUILD))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            e -> {
                                assertThat(e.status()).isEqualTo(410);
                                assertThat(e.error().code()).isEqualTo("PHYSICAL_OBJECTS_REMOVED");
                                assertThat(e.error().buildId()).isEqualTo(BUILD);
                            });
            assertThat(paths).containsExactly("/builds/" + BUILD);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void availableDimensionsReturnCompletedResult() throws Exception {
        var paths = new ArrayList<String>();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/dbt.json"));
        var options = fixture.path("examples");
        String response = null;
        for (var sample : options)
            if (sample.path("model").asText().equals("AvailableDimensionsView")) {
                response = sample.path("value").toString();
                break;
            }
        assertThat(response).isNotNull();
        var server = server(paths, 200, response);
        try {
            var result =
                    client(server)
                            .availableDimensions(
                                    BUILD,
                                    new AvailableDimensionsRequest(List.of("metric.revenue")));
            assertThat(result.dimensions()).isNotNull();
            assertThat(result.buildId()).isEqualTo(BUILD);
            assertThat(paths).containsExactly("/query/available-dimensions?build_id=" + BUILD);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resourceIdsAreEncodedAsSingleSegment() throws Exception {
        var paths = new ArrayList<String>();
        var server =
                server(
                        paths,
                        200,
                        "{\"buildId\":\""
                                + BUILD
                                + "\",\"resourceId\":\"a/b\",\"path\":\"x.yml\",\"content\":\"x\"}");
        try {
            assertThat(client(server).resource(BUILD, "a/b", "source").content()).isEqualTo("x");
            assertThat(paths).containsExactly("/builds/" + BUILD + "/resources/a%2Fb/source");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timePrecisionAndErrorsRoundTrip() throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/dbt.json"));
        for (var sample : fixture.path("examples")) {
            if (sample.path("model").asText().equals("QueryResult")) {
                var paths = new ArrayList<String>();
                var server = server(paths, 200, sample.path("value").toString());
                try {
                    var page = client(server).query(BUILD, null);
                    assertThat(page.buildId()).isEqualTo(BUILD);
                    assertThat(mapper.valueToTree(page).path("rows"))
                            .isEqualTo(sample.path("value").path("rows"));
                    assertThat(page.businessTimezone())
                            .isEqualTo(sample.path("value").path("businessTimezone").asText());
                } finally {
                    server.stop(0);
                }
            }
        }
    }

    @Test
    void rejectsUnexpectedBuildIdentityAndAsyncOptionsAlias() throws Exception {
        var paths = new ArrayList<String>();
        var server = server(paths, 200, "{\"buildId\":\"" + UUID.randomUUID() + "\"}");
        try {
            assertThatThrownBy(() -> client(server).getBuild(BUILD))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class, e -> assertThat(e.status()).isEqualTo(502));
        } finally {
            server.stop(0);
        }
        var sync =
                server(
                        new ArrayList<>(),
                        202,
                        "{\"optionsTaskId\":\""
                                + UUID.randomUUID()
                                + "\",\"buildId\":\""
                                + BUILD
                                + "\",\"state\":\"SUCCEEDED\",\"metricResourceIds\":[\"metric.revenue\"],\"options\":[]}");
        try {
            assertThatThrownBy(
                            () ->
                                    client(sync)
                                            .availableDimensions(
                                                    BUILD,
                                                    new AvailableDimensionsRequest(
                                                            List.of("metric.revenue"))))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class, e -> assertThat(e.status()).isEqualTo(502));
        } finally {
            sync.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(QueryMode.class)
    void querySubmissionReturnsCompleteFirstPage(QueryMode mode) throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/dbt.json"));
        var sample =
                java.util.stream.StreamSupport.stream(fixture.path("examples").spliterator(), false)
                        .filter(value -> value.path("model").asText().equals("QueryResult"))
                        .findFirst()
                        .orElseThrow();
        var response = sample.path("value").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("mode", mode.name());
        var server = server(new ArrayList<>(), 200, response.toString());
        try {
            var result =
                    client(server)
                            .query(
                                    BUILD,
                                    new QueryRequest(
                                            mode,
                                            List.of("metric.revenue"),
                                            List.of(),
                                            List.of(),
                                            null,
                                            null,
                                            List.of(),
                                            100,
                                            null,
                                            null));
            assertThat(result.buildId()).isEqualTo(BUILD);
            assertThat(mapper.valueToTree(result).path("rows")).isEqualTo(response.path("rows"));
            assertThat(result.mode()).isEqualTo(mode);
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void rejectsUnfinishedQueryReturnedAsSuccess(String state) throws Exception {
        var body =
                mapper.writeValueAsString(
                        Map.of("buildId", BUILD, "queryId", UUID.randomUUID(), "state", state));
        var server = server(new ArrayList<>(), 200, body);
        try {
            assertThatThrownBy(() -> client(server).query(BUILD, null))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> assertThat(failure.status()).isEqualTo(502));
        } finally {
            server.stop(0);
        }
    }

    private HttpBuildGateway client(HttpServer server) {
        return new HttpBuildGateway(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2),
                "credential");
    }

    @Test
    void preservesStandardFastApiValidationStatusWithoutExposingInput() throws Exception {
        var server =
                server(
                        new ArrayList<>(),
                        422,
                        """
                        {"detail":[{"type":"less_than_equal","loc":["query","limit"],
                        "msg":"Input should be less than or equal to 200","input":"private-input","ctx":{"le":200}}]}
                        """);
        try {
            assertThatThrownBy(() -> client(server).query(BUILD, null))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(422);
                                assertThat(failure.error().code())
                                        .isEqualTo("REQUEST_VALIDATION_ERROR");
                                assertThat(failure.error().message())
                                        .doesNotContain("private-input");
                                assertThat(failure.error().retryable()).isFalse();
                            });
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "422,INVALID_REQUEST",
        "410,PHYSICAL_OBJECTS_REMOVED",
        "503,STORAGE_UNAVAILABLE"
    })
    void preservesActualServiceErrorEnvelope(int status, String code) throws Exception {
        var server =
                server(
                        new ArrayList<>(),
                        status,
                        mapper.writeValueAsString(
                                Map.of(
                                        "error",
                                        Map.of(
                                                "code",
                                                code,
                                                "message",
                                                "diagnostic",
                                                "retryable",
                                                status == 503))));
        try {
            assertThatThrownBy(() -> client(server).query(BUILD, null))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(status);
                                assertThat(failure.error().code()).isEqualTo(code);
                                assertThat(failure.error().retryable()).isEqualTo(status == 503);
                            });
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "{}",
                "{\"error\":null}",
                "{\"error\":{\"code\":\"BROKEN\"}}",
                "{\"detail\":[{\"unexpected\":true}]}"
            })
    void rejectsMalformedErrorResponse(String body) throws Exception {
        var server = server(new ArrayList<>(), 422, body);
        try {
            assertThatThrownBy(() -> client(server).query(BUILD, null))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> assertThat(failure.status()).isEqualTo(502));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(List<String> paths, int status, String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    paths.add(e.getRequestURI().toASCIIString());
                    assertThat(e.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer credential");
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.sendResponseHeaders(status, bytes.length);
                    try (var out = e.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        return server;
    }
}
