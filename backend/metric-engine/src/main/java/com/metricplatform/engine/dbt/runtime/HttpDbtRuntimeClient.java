package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.EngineDtos.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 有界读取运行服务响应；失败只暴露状态码，不传播可能含凭据的服务响应体。 */
@Component
@ConditionalOnProperty(
        prefix = "metric-platform.dbt-runtime",
        name = "enabled",
        havingValue = "true")
public final class HttpDbtRuntimeClient implements EngineClient {
    private static final String RUNS = "/v1/project-runs",
            QUERIES = "/v1/query-jobs",
            BY_KEY = "/by-key/",
            CATALOG = "/catalog",
            CLEANUP = ":cleanup",
            JSON_CONTENT_TYPE = "application/json",
            CONTENT_TYPE = "Content-Type",
            GET = "GET",
            POST = "POST",
            READY = "READY",
            QUERY_OPTIONS = "/query-options";
    private static final Map<String, String> SUPPORTED_SCHEMAS =
            Map.of(
                    "manifest", "https://schemas.getdbt.com/dbt/manifest/v12.json",
                    "semanticManifest", "dbt-metricflow/0.15.0",
                    "catalog", "https://schemas.getdbt.com/dbt/catalog/v1.json");
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    public HttpDbtRuntimeClient(DbtRuntimeProperties properties) {
        this(properties.getBaseUri(), properties.getTimeout());
    }

    public HttpDbtRuntimeClient(URI baseUri, Duration timeout) {
        if (baseUri == null
                || !("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()) throw new IllegalArgumentException("dbt 运行服务配置无效");
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public RunReceipt submitRun(RunRequest request) {
        return call(POST, RUNS, request, RunReceipt.class);
    }

    @Override
    public RunSnapshot getRun(UUID runId) {
        return validateRun(call(GET, RUNS + "/" + runId, null, RunSnapshot.class));
    }

    @Override
    public RunSnapshot getRunByKey(UUID idempotencyKey) {
        return validateRun(call(GET, RUNS + BY_KEY + idempotencyKey, null, RunSnapshot.class));
    }

    @Override
    public CatalogSnapshot getCatalog(UUID runId) {
        CatalogSnapshot result =
                call(GET, RUNS + "/" + runId + CATALOG, null, CatalogSnapshot.class);
        if (result == null
                || result.resources() == null
                || result.dependencies() == null
                || !SUPPORTED_SCHEMAS.equals(result.artifactSchemas()))
            throw new ServiceFailure(false, 200);
        return result;
    }

    @Override
    public JsonNode queryOptions(UUID runId, List<String> metrics) {
        if (metrics == null || metrics.isEmpty()) throw new IllegalArgumentException("指标不能为空");
        String query =
                metrics.stream()
                        .map(
                                metric ->
                                        "metrics="
                                                + URLEncoder.encode(metric, StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.joining("&"));
        return call(GET, RUNS + "/" + runId + QUERY_OPTIONS + "?" + query, null, JsonNode.class);
    }

    @Override
    public QueryReceipt submitQuery(QueryJobRequest request) {
        return call(POST, QUERIES, request, QueryReceipt.class);
    }

    @Override
    public QuerySnapshot getQuery(UUID queryId) {
        return call(GET, QUERIES + "/" + queryId, null, QuerySnapshot.class);
    }

    @Override
    public QuerySnapshot getQueryByKey(UUID idempotencyKey) {
        return call(GET, QUERIES + BY_KEY + idempotencyKey, null, QuerySnapshot.class);
    }

    @Override
    public void cleanup(UUID runId) {
        call(POST, RUNS + "/" + runId + CLEANUP, null, Void.class);
    }

    private RunSnapshot validateRun(RunSnapshot run) {
        if (run == null || run.runId() == null || run.state() == null)
            throw new ServiceFailure(false, 200);
        if (READY.equals(run.state())
                && (run.commitSha() == null
                        || run.projectDigest() == null
                        || run.profileBindingId() == null
                        || run.configVersion() == null
                        || run.toolchainVersion() == null
                        || run.schemaName() == null
                        || run.manifestDigest() == null
                        || run.semanticManifestDigest() == null
                        || run.runResultsDigest() == null
                        || !Boolean.TRUE.equals(run.allTestsPassed())
                        || !Boolean.TRUE.equals(run.representativeQueryPassed())
                        || !Boolean.TRUE.equals(run.queryCapability())
                        || !Boolean.TRUE.equals(run.relationsVerified())))
            throw new ServiceFailure(false, 200);
        return run;
    }

    private <T> T call(String method, String path, Object body, Class<T> resultType) {
        try {
            HttpRequest.BodyPublisher publisher =
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(
                                    mapper.writeValueAsBytes(body));
            HttpRequest request =
                    HttpRequest.newBuilder(baseUri.resolve(path))
                            .timeout(timeout)
                            .header(CONTENT_TYPE, JSON_CONTENT_TYPE)
                            .method(method, publisher)
                            .build();
            HttpResponse<java.io.InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new ServiceFailure(false, response.statusCode());
                int status = response.statusCode();
                if (status < 200 || status >= 300)
                    throw new ServiceFailure(isTransient(status), status);
                if (resultType == Void.class) return null;
                return mapper.readValue(bytes, resultType);
            }
        } catch (ServiceFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceFailure(true, 0);
        } catch (IOException e) {
            throw new ServiceFailure(true, 0);
        }
    }

    private static boolean isTransient(int status) {
        return status == 408 || status == 429 || status == 502 || status == 503 || status == 504;
    }
}
