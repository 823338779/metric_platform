package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.PublishedCatalogGateway;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 已发布 JSON 协议的有界 HTTP 代理；不建立平台资源投影。 */
@Component
@ConditionalOnProperty(name = "metric.publication.mode", havingValue = "service-proxy")
public class HttpPublishedCatalogClient implements PublishedCatalogGateway {
    private static final String ROOT = "/v2/projects",
            SLASH = "/",
            RELEASES = "/releases/",
            RESOURCES = "/resources/",
            QUERIES = "/queries",
            GET = "GET",
            POST = "POST",
            EMPTY = "",
            PUBLICATION = "/publication",
            CATALOG = "/catalog",
            OPTIONS = "/query-options",
            CONTENT_TYPE = "Content-Type",
            JSON = "application/json",
            UNAVAILABLE = "runtime_unavailable",
            INVALID = "invalid_runtime_response";
    private static final String BRANCHES = "/branches",
            RELEASE_COLLECTION = "/releases",
            DIFF = "/diff";
    private static final Set<String> VIEWS = Set.of(EMPTY, "lineage", "source", "native-details");
    private static final Map<Integer, String> ERRORS =
            Map.of(
                    401,
                    "service_credential_required",
                    404,
                    "not_found",
                    409,
                    "idempotency_conflict",
                    410,
                    "release_gone",
                    422,
                    "invalid_query_selection");
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    public HttpPublishedCatalogClient(DbtRuntimeProperties properties) {
        this(properties.getBaseUri(), properties.getTimeout());
    }

    public HttpPublishedCatalogClient(URI baseUri, Duration timeout) {
        if (baseUri == null
                || !Set.of("http", "https").contains(baseUri.getScheme())
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()) throw new IllegalArgumentException("发布服务地址或超时配置无效");
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String project(String id) {
        return ROOT + SLASH + encode(id);
    }

    private static String releasePath(String id, UUID version) {
        return project(id) + RELEASES + version;
    }

    public JsonNode projects() {
        var value = call(GET, ROOT, null, true);
        value.forEach(PublishedProtocol::publication);
        return value;
    }

    public JsonNode publication(String id) {
        return PublishedProtocol.publication(call(GET, project(id) + PUBLICATION, null, false));
    }

    public JsonNode releases(String id) {
        var value = call(GET, project(id) + "/releases", null, true);
        value.forEach(PublishedProtocol::release);
        return value;
    }

    public JsonNode release(String id, UUID version) {
        return PublishedProtocol.release(call(GET, releasePath(id, version), null, false));
    }

    public JsonNode catalog(String id, UUID version, String q, String kind, int page, int size) {
        return PublishedProtocol.catalog(
                call(
                        GET,
                        releasePath(id, version)
                                + CATALOG
                                + "?page="
                                + page
                                + "&size="
                                + size
                                + "&q="
                                + encode(q == null ? EMPTY : q)
                                + (kind == null || kind.isBlank()
                                        ? EMPTY
                                        : "&kind=" + encode(kind)),
                        null,
                        false));
    }

    public JsonNode resource(String id, UUID version, String resourceId, String view) {
        if (!VIEWS.contains(view)) throw new IllegalArgumentException("未知目录子资源");
        var value =
                call(
                        GET,
                        releasePath(id, version)
                                + RESOURCES
                                + encode(resourceId)
                                + (view.isEmpty() ? EMPTY : SLASH + view),
                        null,
                        false);
        return view.isEmpty() ? PublishedProtocol.resource(value) : value;
    }

    public JsonNode queryOptions(String id, JsonNode request) {
        return PublishedProtocol.options(call(POST, project(id) + OPTIONS, request, false));
    }

    public JsonNode submitQuery(String id, JsonNode request) {
        return PublishedProtocol.query(
                call(POST, project(id) + QUERIES, DbtQueryTimeAdapter.normalize(request), false));
    }

    public JsonNode query(String id, UUID queryId) {
        return PublishedProtocol.query(
                call(GET, project(id) + QUERIES + SLASH + queryId, null, false));
    }

    public JsonNode compatibility(String id, String operation, JsonNode request) {
        if (!Set.of("queries", "query-options").contains(operation))
            throw new IllegalArgumentException("未知兼容操作");
        return call(
                POST,
                project(id) + "/compatibility/" + operation,
                "queries".equals(operation) ? DbtQueryTimeAdapter.normalize(request) : request,
                false);
    }

    // 分支请求仅使用类型化 UUID 和受控路径，不设置共享的当前分支状态。
    private static String branchPath(String id, UUID branchId) {
        return project(id) + BRANCHES + SLASH + branchId;
    }

    private static String branchRelease(String id, UUID branchId, UUID releaseId) {
        return branchPath(id, branchId) + RELEASES + releaseId;
    }

    public JsonNode branches(String id) {
        var value = call(GET, project(id) + BRANCHES, null, true);
        value.forEach(PublishedProtocol::branch);
        return value;
    }

    public JsonNode branch(String id, UUID branchId) {
        return PublishedProtocol.branch(scoped(GET, branchPath(id, branchId), null, branchId));
    }

    public JsonNode publication(String id, UUID branchId) {
        return PublishedProtocol.publication(
                scoped(GET, branchPath(id, branchId) + PUBLICATION, null, branchId));
    }

    public JsonNode releases(String id, UUID branchId) {
        var value = call(GET, branchPath(id, branchId) + RELEASE_COLLECTION, null, true);
        value.forEach(item -> PublishedProtocol.release(PublishedProtocol.scoped(item, branchId)));
        return value;
    }

    public JsonNode release(String id, UUID branchId, UUID version) {
        return PublishedProtocol.release(
                scoped(GET, branchRelease(id, branchId, version), null, branchId));
    }

    public JsonNode catalog(
            String id, UUID branchId, UUID version, String q, String kind, int page, int size) {
        return PublishedProtocol.catalog(
                scoped(
                        GET,
                        branchRelease(id, branchId, version)
                                + CATALOG
                                + "?page="
                                + page
                                + "&size="
                                + size
                                + "&q="
                                + encode(q == null ? EMPTY : q)
                                + (kind == null || kind.isBlank()
                                        ? EMPTY
                                        : "&kind=" + encode(kind)),
                        null,
                        branchId));
    }

    public JsonNode resource(
            String id, UUID branchId, UUID version, String resourceId, String view) {
        if (!VIEWS.contains(view)) throw new IllegalArgumentException("未知目录子资源");
        var value =
                scoped(
                        GET,
                        branchRelease(id, branchId, version)
                                + RESOURCES
                                + encode(resourceId)
                                + (view.isEmpty() ? EMPTY : SLASH + view),
                        null,
                        branchId);
        return view.isEmpty() ? PublishedProtocol.resource(value) : value;
    }

    public JsonNode diff(String id, UUID branchId, UUID version) {
        return scoped(GET, branchRelease(id, branchId, version) + DIFF, null, branchId);
    }

    public JsonNode queryOptions(String id, UUID branchId, JsonNode request) {
        return PublishedProtocol.options(
                scoped(POST, branchPath(id, branchId) + OPTIONS, request, branchId));
    }

    public JsonNode submitQuery(String id, UUID branchId, JsonNode request) {
        return PublishedProtocol.query(
                scoped(
                        POST,
                        branchPath(id, branchId) + QUERIES,
                        DbtQueryTimeAdapter.normalize(request),
                        branchId));
    }

    public JsonNode query(String id, UUID branchId, UUID queryId) {
        return PublishedProtocol.query(
                scoped(GET, branchPath(id, branchId) + QUERIES + SLASH + queryId, null, branchId));
    }

    private JsonNode scoped(String method, String path, JsonNode body, UUID branchId) {
        return PublishedProtocol.scoped(call(method, path, body, false), branchId);
    }

    private JsonNode call(String method, String path, JsonNode body, boolean array) {
        try {
            var publisher =
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(
                                    mapper.writeValueAsBytes(body));
            var request =
                    HttpRequest.newBuilder(baseUri.resolve(path))
                            .timeout(timeout)
                            .header(CONTENT_TYPE, JSON)
                            .method(method, publisher)
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                int status = response.statusCode();
                if (ERRORS.containsKey(status)) throw new Failure(status, ERRORS.get(status));
                if (status < 200 || status >= 300) throw new Failure(503, UNAVAILABLE);
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new Failure(502, INVALID);
                JsonNode value = mapper.readTree(bytes);
                if (value == null || (array ? !value.isArray() : !value.isObject()))
                    throw new Failure(502, INVALID);
                return value;
            }
        } catch (JsonProcessingException error) {
            throw new Failure(502, INVALID);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure(503, UNAVAILABLE);
        } catch (IOException error) {
            throw new Failure(503, UNAVAILABLE);
        }
    }
}
