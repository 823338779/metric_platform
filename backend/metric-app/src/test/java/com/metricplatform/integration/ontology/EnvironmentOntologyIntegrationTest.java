package com.metricplatform.integration.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.AssetGrantRepository;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest(
        properties = {
            "metric-platform.operations.token=fixture-only",
            "spring.datasource.hikari.maximum-pool-size=2",
            "EXECUTION_INSTANCES={\"warehouse\":{\"type\":\"starrocks\",\"hosts\":[\"localhost\"],\"port\":9030}}"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EnvironmentOntologyIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID BUILD = UUID.randomUUID();
    private static final UUID NEXT_BUILD = UUID.randomUUID();
    private static volatile UUID activeBuild = BUILD;
    private static final String REPO = "https://example.test/metrics.git";
    private static final String MODEL = "model.sales.orders";
    private static final String ENVIRONMENTS = "/api/dbt/environments";
    private static final GenericContainer<?> GRAPH =
            new GenericContainer<>("neo4j:2026.10.1")
                    .withExposedPorts(7687)
                    .withEnv("NEO4J_AUTH", "neo4j/fixture-password")
                    .waitingFor(Wait.forLogMessage(".*Started\\..*\\n", 1));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static HttpServer upstream;
    private static volatile UUID deployedEnvironment;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) throws IOException {
        GRAPH.start();
        REDIS.start();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    var node =
                            Map.of(
                                    "unique_id",
                                    MODEL,
                                    "resource_type",
                                    "model",
                                    "name",
                                    "orders",
                                    "fqn",
                                    List.of("sales", "orders"),
                                    "columns",
                                    Map.of(
                                            "order_id",
                                            Map.of("name", "order_id", "data_type", "bigint")));
                    Object response;
                    if (path.equals("/deployments/current")) {
                        response =
                                Map.of(
                                        "activeBuildId",
                                        activeBuild,
                                        "repository",
                                        REPO,
                                        "environmentId",
                                        deployedEnvironment,
                                        "branchName",
                                        "main",
                                        "version",
                                        1,
                                        "desiredGeneration",
                                        1);
                    } else if (path.startsWith("/builds/" + BUILD + "/")
                            && !BUILD.equals(activeBuild)) {
                        exchange.sendResponseHeaders(410, -1);
                        exchange.close();
                        return;
                    } else if (path.equals("/builds/" + NEXT_BUILD + "/catalog")) {
                        var duplicate = new HashMap<String, Object>(node);
                        duplicate.put("unique_id", "model.other.orders");
                        duplicate.put("fqn", List.of("other", "orders"));
                        var distinct = new HashMap<String, Object>(node);
                        distinct.put("name", "customers");
                        distinct.put("unique_id", "model.sales.customers");
                        distinct.put("fqn", List.of("sales", "customers"));
                        response = List.of(node, duplicate, distinct);
                    } else if (path.equals("/builds/" + BUILD + "/catalog")) {
                        response = List.of(node);
                    } else if (path.equals("/builds/" + activeBuild + "/resources/" + MODEL)) {
                        response =
                                Map.of(
                                        "buildId",
                                        activeBuild,
                                        "resourceId",
                                        MODEL,
                                        "resource",
                                        node);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    byte[] body = JSON.writeValueAsBytes(response);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(body);
                    }
                });
        upstream.start();
        registry.add(
                "metric-platform.dbt-runtime.base-uri",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add(
                "metric-platform.ontology.uri",
                () -> "bolt://" + GRAPH.getHost() + ":" + GRAPH.getMappedPort(7687));
        registry.add("metric-platform.ontology.username", () -> "neo4j");
        registry.add("metric-platform.ontology.password", () -> "fixture-password");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @AfterAll
    static void close() {
        if (upstream != null) upstream.stop(0);
        REDIS.stop();
        GRAPH.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired TenantFixtureMapper fixtures;
    @Autowired AssetGrantRepository assets;

    @Test
    void managerCreatesBoundObjectInOneEnvironmentWithRealGraphAndTenantStores() throws Exception {
        var account =
                Map.of("username", UUID.randomUUID().toString(), "password", "fixture-password");
        String tenant =
                call(
                                post("/api/ops/tenants")
                                        .header("X-Operations-Token", "fixture-only"),
                                account,
                                201)
                        .path("tenant_id")
                        .asText();
        String token =
                "Bearer "
                        + call(post("/api/v1/auth/token"), account, 200)
                                .path("accessToken")
                                .asText();
        UUID test = environment(token), production = environment(token);
        deployedEnvironment = test;
        fixtures.bindBuild(tenant, BUILD, test);
        try (var ignored = TenantScope.open(tenant)) {
            assets.bind(BUILD, new DeploymentKey(REPO, test, "main"));
        }
        String graph = ENVIRONMENTS + "/" + test + "/ontology";
        var choices = call(get(graph + "/datasets").header("Authorization", token), null, 200);
        String assetId = choices.path("datasets").get(0).path("assetId").asText();
        var columns =
                call(
                        get(graph + "/datasets/" + assetId + "/columns")
                                .param("buildId", BUILD.toString())
                                .header("Authorization", token),
                        null,
                        200);
        assertThat(columns.at("/columns/0/name").asText()).isEqualTo("order_id");
        UUID object = UUID.randomUUID(), property = UUID.randomUUID();
        var order =
                Map.of(
                        "id",
                        object,
                        "name",
                        "订单",
                        "datasetAssetId",
                        assetId,
                        "primaryPropertyId",
                        property,
                        "properties",
                        List.of(
                                Map.of(
                                        "id",
                                        property,
                                        "name",
                                        "订单编号",
                                        "columnName",
                                        "order_id",
                                        "declaredType",
                                        "bigint")));
        var create = Map.of("expectedRevision", 0, "sourceBuildId", BUILD, "object", order);
        call(post(graph + "/objects").header("Authorization", token), create, 201);
        call(post(graph + "/objects").header("Authorization", token), create, 409);
        var result = call(get(graph).header("Authorization", token), null, 200);
        assertThat(result.at("/objects/0/id").asText()).isEqualTo(object.toString());
        assertThat(result.at("/bindingReport/buildId").asText()).isEqualTo(BUILD.toString());
        var other =
                call(
                        get(ENVIRONMENTS + "/" + production + "/ontology")
                                .header("Authorization", token),
                        null,
                        200);
        assertThat(other.path("objects").size()).isZero();
        fixtures.bindBuild(tenant, NEXT_BUILD, test);
        try (var ignored = TenantScope.open(tenant)) {
            assets.bind(NEXT_BUILD, new DeploymentKey(REPO, test, "main"));
        }
        activeBuild = NEXT_BUILD;
        var next = call(get(graph + "/datasets").header("Authorization", token), null, 200);
        assertThat(next.path("datasets").size()).isOne();
        assertThat(next.at("/datasets/0/name").asText()).isEqualTo("customers");
        var report =
                call(get(graph).header("Authorization", token), null, 200).path("bindingReport");
        assertThat(report.path("buildId").asText()).isEqualTo(NEXT_BUILD.toString());
        assertThat(report.path("findings").findValuesAsText("reason")).contains("AMBIGUOUS_ASSET");
        GRAPH.stop();
        call(get(graph).header("Authorization", token), null, 503);
        var resource =
                call(
                        get("/api/dbt/builds/" + NEXT_BUILD + "/resources/" + MODEL)
                                .header("Authorization", token),
                        null,
                        200);
        assertThat(resource.path("resourceId").asText()).isEqualTo(MODEL);
    }

    private UUID environment(String token) throws Exception {
        var input =
                Map.of(
                        "name",
                        "fixture",
                        "connection",
                        Map.of(
                                "type",
                                "starrocks",
                                "instanceId",
                                "warehouse",
                                "host",
                                "localhost",
                                "port",
                                9030),
                        "credentialRef",
                        "test",
                        "catalog",
                        "default_catalog",
                        "schema",
                        "onto_" + UUID.randomUUID().toString().replace("-", ""),
                        "threads",
                        2,
                        "businessTimezone",
                        "Asia/Shanghai");
        return UUID.fromString(
                call(post(ENVIRONMENTS).header("Authorization", token), input, 201)
                        .path("environmentId")
                        .asText());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object input, int statusCode)
            throws Exception {
        if (input != null)
            request.contentType("application/json").content(JSON.writeValueAsBytes(input));
        return JSON.readTree(
                mvc.perform(request)
                        .andExpect(status().is(statusCode))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray());
    }
}
