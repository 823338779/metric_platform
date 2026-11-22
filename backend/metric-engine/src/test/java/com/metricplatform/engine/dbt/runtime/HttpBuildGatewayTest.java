package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class HttpBuildGatewayTest {
    private static final String SERVICE_AUTHENTICATION_FAILED = "SERVICE_AUTHENTICATION_FAILED";
    private static final String CREDENTIAL_ERROR =
            "{\"detail\":{\"code\":\"CREDENTIAL_REQUIRED\",\"message\":\"credential"
                    + " required\",\"retryable\":false}}";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403})
    void serviceAuthenticationFailureDoesNotExpirePlatformLogin(int status) throws Exception {
        var server = server(new ArrayList<>(), status, CREDENTIAL_ERROR);
        try {
            assertThatThrownBy(() -> client(server).query(BUILD, null))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(502);
                                assertThat(failure.error().code())
                                        .isEqualTo(SERVICE_AUTHENTICATION_FAILED);
                                assertThat(failure.error().retryable()).isFalse();
                            });
        } finally {
            server.stop(0);
        }
    }

    private static final UUID BUILD = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deploymentChecksContactGitOnlyOnExplicitRefresh() throws Exception {
        var paths = new ArrayList<String>();
        var upstream = server(paths, 200, "{}");
        try {
            var gateway = client(upstream);
            var key = new DeploymentKey("repo", BUILD, "main");
            gateway.current(key);
            gateway.current(key, true);
            String path = "/deployments/current?repository=repo&branchName=main&environmentId=" + BUILD;
            assertThat(paths).containsExactly(path + "&refreshSource=false", path + "&refreshSource=true");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void typedHistoryValidatesEveryExecutionSnapshotAgainstTheRequestedEnvironment()
            throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/dbt.json"));
        var page = mapper.createObjectNode();
        var items = page.putArray("items");
        var build = fixture.path("examples").findParent("buildStatus");
        items.add(build);
        var environment = UUID.fromString(build.at("/execution/environmentId").asText());
        var filter = new BuildFilter(environment, "", "feature/a", null, "next page", 3);
        var paths = new ArrayList<String>();
        var valid = server(paths, 200, page.toString());
        try {
            assertThat(client(valid).listBuilds(filter).items())
                    .singleElement()
                    .extracting(BuildView::buildId)
                    .isEqualTo(BUILD);
            assertThat(paths)
                    .containsExactly(
                            "/builds?environmentId="
                                    + environment
                                    + "&repository=&branchName=feature%2Fa&cursor=next%20page&limit=3");
        } finally {
            valid.stop(0);
        }
        var other = (ObjectNode) build.deepCopy();
        items.add(other);
        ((ObjectNode) other.path("execution")).put("environmentId", UUID.randomUUID().toString());
        assertInvalidHistory(page.toString(), filter);
        other.putObject("execution").put("environmentId", environment.toString());
        assertInvalidHistory(page.toString(), filter);
    }

    private void assertInvalidHistory(String body, BuildFilter filter) throws Exception {
        var server = server(new ArrayList<>(), 200, body);
        try {
            assertThatThrownBy(() -> client(server).listBuilds(filter))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(502);
                                assertThat(failure.error().code())
                                        .isEqualTo("INVALID_SERVICE_RESPONSE");
                            });
        } finally {
            server.stop(0);
        }
    }

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
                                    BUILD, new AvailableDimensionsRequest(List.of("revenue")));
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
                                + "\",\"resourceId\":\"a/b\",\"resource\":{},\"source\":{\"path\":\"x.yml\",\"content\":\"x\"}}");
        try {
            assertThat(client(server).resource(BUILD, "a/b").source().content()).isEqualTo("x");
            assertThat(paths).containsExactly("/builds/" + BUILD + "/resources/a%2Fb");
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
                                + "\",\"state\":\"SUCCEEDED\",\"metrics\":[\"revenue\"],\"options\":[]}");
        try {
            assertThatThrownBy(
                            () ->
                                    client(sync)
                                            .availableDimensions(
                                                    BUILD,
                                                    new AvailableDimensionsRequest(
                                                            List.of("revenue"))))
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
        if (mode == QueryMode.EXPLAIN) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("sql", "select 1");
            ((com.fasterxml.jackson.databind.node.ObjectNode) response)
                    .remove(List.of("columns", "rows", "resultTruncated"));
        }
        var server = server(new ArrayList<>(), 200, response.toString());
        try {
            var result =
                    client(server)
                            .query(
                                    BUILD,
                                    new QueryRequest(
                                            mode,
                                            List.of("revenue"),
                                            List.of(),
                                            List.of(),
                                            null,
                                            null,
                                            List.of(),
                                            100,
                                            null,
                                            null));
            assertThat(result.buildId()).isEqualTo(BUILD);
            if (mode == QueryMode.EXPLAIN) assertThat(result.sql()).isEqualTo("select 1");
            else
                assertThat(mapper.valueToTree(result).path("rows"))
                        .isEqualTo(response.path("rows"));
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
