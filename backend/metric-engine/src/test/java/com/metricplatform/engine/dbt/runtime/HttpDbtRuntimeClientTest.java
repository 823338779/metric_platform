package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpDbtRuntimeClientTest {
    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final UUID RUN_ID = UUID.fromString("d1a00000-0000-0000-0000-000000000001");
    private static final UUID KEY = UUID.fromString("d1a00000-0000-0000-0000-000000000002");

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    @Test
    void submitsFixedBuildInputsWithoutRemoteOrCredentials() {
        server.createContext(
                "/v1/project-runs",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    respond(exchange, 202, "{\"runId\":\"" + RUN_ID + "\"}");
                });
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));
        var request =
                new EngineDtos.RunRequest(
                        "sales",
                        "a".repeat(40),
                        "1".repeat(64),
                        "starrocks-profile",
                        "config-1",
                        KEY);

        assertThat(client.submitRun(request).runId()).isEqualTo(RUN_ID);
        assertThat(requestBody.get()).contains("\"profileBindingId\":\"starrocks-profile\"");
        assertThat(requestBody.get()).doesNotContain("remote", "password", "jdbc:");
    }

    @Test
    void findsTaskByKeyAfterSubmissionResultIsUnknown() {
        server.createContext(
                "/v1/project-runs/by-key/" + KEY,
                exchange -> respond(exchange, 200, runningSnapshot()));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));

        assertThat(client.getRunByKey(KEY).runId()).isEqualTo(RUN_ID);
    }

    @Test
    void distinguishesPermanentAndTransientServiceErrors() {
        server.createContext("/v1/project-runs", exchange -> respond(exchange, 422, "bad data"));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));
        var request =
                new EngineDtos.RunRequest(
                        "sales",
                        "a".repeat(40),
                        "1".repeat(64),
                        "starrocks-profile",
                        "config-1",
                        KEY);

        assertThatThrownBy(() -> client.submitRun(request))
                .isInstanceOf(EngineClient.ServiceFailure.class)
                .extracting("transientFailure")
                .isEqualTo(false);
        server.removeContext("/v1/project-runs");
        server.createContext(
                "/v1/project-runs", exchange -> respond(exchange, 503, "temporarily down"));
        assertThatThrownBy(() -> client.submitRun(request))
                .isInstanceOf(EngineClient.ServiceFailure.class)
                .extracting("transientFailure")
                .isEqualTo(true);
    }

    @Test
    void acceptsReadyEmptyRunWithExplicitlyFalseQueryProof() {
        server.createContext(
                "/v1/project-runs/" + RUN_ID,
                exchange ->
                        respond(
                                exchange,
                                200,
                                """
                        {"runId":"%s","state":"READY","commitSha":"%s","projectDigest":"%s",
                        "profileBindingId":"profile","configVersion":"config-1","toolchainVersion":"dbt/mf",
                        "schemaName":"run_empty","manifestDigest":"manifest","semanticManifestDigest":"semantic",
                        "runResultsDigest":"results","allTestsPassed":true,"relationsVerified":true,
                        "representativeQueryPassed":false,"queryCapability":false}
                        """
                                        .formatted(RUN_ID, "a".repeat(40), "1".repeat(64))));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));
        assertThat(client.getRun(RUN_ID).queryCapability()).isFalse();
        assertThat(client.getRun(RUN_ID).representativeQueryPassed()).isFalse();
    }

    @Test
    void rejectsReadyWithoutQueryableStarRocksCapability() {
        server.createContext(
                "/v1/project-runs/" + RUN_ID,
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"runId\":\""
                                        + RUN_ID
                                        + "\",\"state\":\"READY\",\"commitSha\":\""
                                        + "a".repeat(40)
                                        + "\",\"projectDigest\":\""
                                        + "1".repeat(64)
                                        + "\",\"schemaName\":\"release_a\",\"allTestsPassed\":true}"));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.getRun(RUN_ID))
                .isInstanceOf(EngineClient.ServiceFailure.class)
                .extracting("transientFailure")
                .isEqualTo(false);
    }

    @Test
    void getsCatalogAndUsesPinnedRunForQuery() {
        UUID queryId = UUID.fromString("d1a00000-0000-0000-0000-000000000003");
        server.createContext(
                "/v1/project-runs/" + RUN_ID + "/catalog",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"resources\":[],\"dependencies\":[],\"artifactSchemas\":{\"manifest\":\"https://schemas.getdbt.com/dbt/manifest/v12.json\",\"semanticManifest\":\"dbt-metricflow/0.15.0\",\"catalog\":\"https://schemas.getdbt.com/dbt/catalog/v1.json\"}}"));
        server.createContext(
                "/v1/query-jobs",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    respond(exchange, 202, "{\"queryId\":\"" + queryId + "\"}");
                });
        server.createContext(
                "/v1/query-jobs/by-key/" + KEY,
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"queryId\":\"" + queryId + "\",\"state\":\"RUNNING\"}"));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));

        assertThat(client.getCatalog(RUN_ID).resources()).isEmpty();
        assertThat(
                        client.submitQuery(
                                        new EngineDtos.QueryJobRequest(
                                                RUN_ID,
                                                KEY,
                                                "QUERY",
                                                List.of("revenue"),
                                                List.of(),
                                                List.of(),
                                                100,
                                                null))
                                .queryId())
                .isEqualTo(queryId);
        assertThat(requestBody.get()).contains(RUN_ID.toString()).doesNotContain("select ");
        assertThat(client.getQueryByKey(KEY).queryId()).isEqualTo(queryId);
    }

    @Test
    void queryOptionsUsesFixedRunAndEncodedMetricNames() {
        var requested = new AtomicReference<String>();
        server.createContext(
                "/v1/project-runs/" + RUN_ID + "/query-options",
                exchange -> {
                    requested.set(exchange.getRequestURI().getRawQuery());
                    respond(exchange, 200, "{\"metrics\":[],\"dimensions\":[]}");
                });
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));
        assertThat(client.queryOptions(RUN_ID, List.of("gross revenue", "orders")).get("metrics"))
                .isNotNull();
        assertThat(requested.get()).isEqualTo("metrics=gross+revenue&metrics=orders");
    }

    @Test
    void catalogRejectsMissingArtifactVersions() {
        server.createContext(
                "/v1/project-runs/" + RUN_ID + "/catalog",
                exchange -> respond(exchange, 200, "{\"resources\":[],\"dependencies\":[]}"));
        var client = new HttpDbtRuntimeClient(baseUri, Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.getCatalog(RUN_ID))
                .isInstanceOf(EngineClient.ServiceFailure.class);
    }

    private static String runningSnapshot() {
        return "{\"runId\":\"" + RUN_ID + "\",\"state\":\"BUILDING\"}";
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
