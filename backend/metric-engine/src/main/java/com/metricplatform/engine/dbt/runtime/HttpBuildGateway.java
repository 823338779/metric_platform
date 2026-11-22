package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 唯一执行快照 HTTP 适配；不持有部署指针，不重解释构建的业务时区。 */
@Component
public class HttpBuildGateway implements BuildGateway {
    private static final String BUILDS = "/builds",
            DEPLOYMENTS = "/deployments",
            QUERY = "/query",
            AVAILABLE_DIMENSIONS = QUERY + "/available-dimensions";
    private static final String GET = "GET",
            POST = "POST",
            SLASH = "/",
            EMPTY = "",
            AUTHORIZATION = "Authorization",
            BEARER = "Bearer ",
            CONTENT_TYPE = "Content-Type",
            JSON = "application/json";
    private static final String REPOSITORY = "repository",
            BRANCH = "branchName",
            ENVIRONMENT = "environmentId",
            BUILD_STATUS = "buildStatus",
            CURSOR = "cursor",
            LIMIT = "limit",
            SELECTOR = "selector",
            BUILD_ID = "build_id",
            DETAIL = "detail",
            ERROR = "error",
            CODE = "code",
            ERROR_MESSAGE = "message",
            RETRYABLE = "retryable",
            LOCATION = "loc",
            MESSAGE = "msg",
            ERROR_TYPE = "type",
            REQUEST_VALIDATION_ERROR = "REQUEST_VALIDATION_ERROR";
    private static final String SERVICE_AUTHENTICATION_FAILED = "SERVICE_AUTHENTICATION_FAILED";
    private static final String CATALOG = "/catalog",
            RESOURCES = "/resources/",
            LINEAGE = "/lineage";
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** 原生资源列表和源码不分页，读取边界与服务默认单产物上限一致。 */
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;

    /** 固定运行服务地址。 */
    private final URI baseUri;

    /** 每次请求的最大等待时间。 */
    private final Duration timeout;

    /** 同步可用维度与查询的有界等待时间。 */
    private final Duration queryTimeout;

    /** 服务访问凭据，不来自浏览器。 */
    private final String token;

    /** 禁止 HTTP 自动跳转，防止凭据泄漏。 */
    private final HttpClient http;

    /** 保留未知资源属性中的 decimal 和 bigint 精度。 */
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @Autowired
    public HttpBuildGateway(DbtRuntimeProperties properties) {
        this(
                properties.getBaseUri(),
                properties.getTimeout(),
                properties.getQueryTimeout(),
                properties.getServiceToken());
    }

    public HttpBuildGateway(URI uri, Duration timeout, String token) {
        this(uri, timeout, timeout, token);
    }

    public HttpBuildGateway(URI uri, Duration timeout, Duration queryTimeout, String token) {
        if (uri == null || !Set.of("http", "https").contains(uri.getScheme()))
            throw new IllegalArgumentException("运行服务地址无效");
        this.baseUri = uri;
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || queryTimeout == null
                || queryTimeout.isZero()
                || queryTimeout.isNegative()) throw new IllegalArgumentException("运行服务超时必须为正数");
        this.timeout = timeout;
        this.queryTimeout = queryTimeout;
        this.token = token;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public BuildView getBuild(UUID id) {
        return scoped(id, call(GET, BUILDS + SLASH + id, null, BuildView.class));
    }

    public BuildPage listBuilds(BuildFilter f) {
        var page = buildHistory(f);
        try {
            return mapper.treeToValue(page, BuildPage.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid();
        }
    }

    JsonNode buildHistory(BuildFilter f) {
        var page =
                call(
                        GET,
                        BUILDS
                                + parameters(
                                        ENVIRONMENT,
                                        f.environmentId(),
                                        BUILD_STATUS,
                                        f.buildStatus(),
                                        REPOSITORY,
                                        f.repository(),
                                        BRANCH,
                                        f.branchName(),
                                        CURSOR,
                                        f.cursor(),
                                        LIMIT,
                                        f.limit()),
                        null,
                        JsonNode.class);
        var items = page.path("items");
        if (!items.isArray()) throw BuildResponse.invalid();
        for (var item : items) BuildResponse.requireEnvironment(item, f.environmentId());
        return page;
    }

    public DeploymentTargetView current(DeploymentKey k, boolean refreshSource) {
        return call(
                GET,
                DEPLOYMENTS
                        + "/current"
                        + parameters(
                                REPOSITORY,
                                k.repository(),
                                BRANCH,
                                k.branchName(),
                                ENVIRONMENT,
                                k.environmentId(),
                                "refreshSource",
                                refreshSource),
                null,
                DeploymentTargetView.class);
    }

    public DeploymentPage listDeployments(DeploymentFilter f) {
        return call(
                GET,
                DEPLOYMENTS
                        + parameters(
                                REPOSITORY,
                                f.repository(),
                                BRANCH,
                                f.branchName(),
                                ENVIRONMENT,
                                f.environmentId(),
                                CURSOR,
                                f.cursor(),
                                LIMIT,
                                f.limit()),
                null,
                DeploymentPage.class);
    }

    public JsonNode catalog(UUID id) {
        // dbt ls 返回资源数组；版本由请求 URL 固定，完整保留原生字段。
        var result = call(GET, BUILDS + SLASH + id + CATALOG, null, JsonNode.class);
        if (!result.isArray()) throw invalid();
        return result;
    }

    public ResourceView resource(UUID id, String resource) {
        var result =
                scoped(
                        id,
                        call(
                                GET,
                                BUILDS + SLASH + id + RESOURCES + encode(resource),
                                null,
                                ResourceView.class));
        if (!resource.equals(result.resourceId()) || result.resource() == null) throw invalid();
        return result;
    }

    public JsonNode lineage(UUID id, String selector) {
        var result =
                call(
                        GET,
                        BUILDS + SLASH + id + LINEAGE + parameters(SELECTOR, selector),
                        null,
                        JsonNode.class);
        if (!result.isArray()) throw invalid();
        return result;
    }

    public AvailableDimensionsView availableDimensions(
            UUID id, AvailableDimensionsRequest request) {
        var result =
                scoped(
                        id,
                        call(
                                POST,
                                AVAILABLE_DIMENSIONS + parameters(BUILD_ID, id),
                                request,
                                AvailableDimensionsView.class));
        if (result.dimensions() == null || result.metrics() == null) throw invalid();
        return result;
    }

    public ResultPage query(UUID id, QueryRequest request) {
        var result =
                scoped(id, call(POST, QUERY + parameters(BUILD_ID, id), request, ResultPage.class));
        // 同步服务返回完整结果，不再持久化查询身份或分页状态。
        if (result.mode() == null) throw invalid();
        if (result.mode() == QueryMode.EXPLAIN) {
            if (result.sql() == null) throw invalid();
        } else if (result.columns() == null || result.rows() == null) throw invalid();
        if (request != null && result.mode() != request.mode()) throw invalid();
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String parameters(Object... pairs) {
        var values = new StringJoiner("&", "?", EMPTY);
        for (int i = 0; i < pairs.length; i += 2)
            if (pairs[i + 1] != null) values.add(pairs[i] + "=" + encode(pairs[i + 1].toString()));
        return values.toString();
    }

    <T> T call(String method, String path, Object body, Class<T> type) {
        try {
            var request =
                    HttpRequest.newBuilder(baseUri.resolve(path))
                            .timeout(POST.equals(method) ? queryTimeout : timeout)
                            .header(CONTENT_TYPE, JSON);
            if (token != null && !token.isBlank()) request.header(AUTHORIZATION, BEARER + token);
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(
                                    mapper.writeValueAsBytes(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                int maxBytes =
                        GET.equals(method) && (path.endsWith(CATALOG) || path.contains(RESOURCES))
                                ? MAX_ARTIFACT_BYTES
                                : MAX_BYTES;
                byte[] bytes = stream.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw invalid();
                // 服务凭据与平台登录身份独立，不能将上游认证失败透传为浏览器登录过期。
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new Failure(
                            502,
                            new ErrorView(
                                    SERVICE_AUTHENTICATION_FAILED,
                                    "dbt-service 认证失败，请检查平台与服务的令牌配置",
                                    false,
                                    null,
                                    null));
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    var node = mapper.readTree(bytes);
                    if (node.has(ERROR)) node = node.get(ERROR);
                    else if (node.has(DETAIL)) node = node.get(DETAIL);
                    // FastAPI 参数校验使用标准 detail 数组，不是业务错误对象；不回传其中的 input。
                    if (response.statusCode() == 422 && node.isArray() && !node.isEmpty()) {
                        for (var issue : node) {
                            if (!issue.path(LOCATION).isArray()
                                    || !issue.path(MESSAGE).isTextual()
                                    || !issue.path(ERROR_TYPE).isTextual()) throw invalid();
                        }
                        throw new Failure(
                                422,
                                new ErrorView(
                                        REQUEST_VALIDATION_ERROR,
                                        "运行服务请求参数不符合契约",
                                        false,
                                        null,
                                        null));
                    }
                    if (!node.path(CODE).isTextual()
                            || !node.path(ERROR_MESSAGE).isTextual()
                            || !node.path(RETRYABLE).isBoolean()) throw invalid();
                    throw new Failure(
                            response.statusCode(), mapper.treeToValue(node, ErrorView.class));
                }
                if (POST.equals(method)
                        && response.statusCode() != 200
                        && !(path.equals("/builds") && response.statusCode() == 202))
                    throw invalid();
                T result = mapper.readValue(bytes, type);
                if (result == null) throw invalid();
                return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid();
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /** 返回身份必须与请求固定构建一致，避免错误服务响应污染页面缓存。 */
    private static <T> T scoped(UUID id, T value) {
        UUID actual =
                switch (value) {
                    case BuildView v -> v.buildId();
                    case ResourceView v -> v.buildId();
                    case AvailableDimensionsView v -> v.buildId();
                    case ResultPage v -> v.buildId();
                    default -> null;
                };
        if (!id.equals(actual)) throw invalid();
        return value;
    }

    private static Failure invalid() {
        return new Failure(
                502, new ErrorView("INVALID_SERVICE_RESPONSE", "运行服务响应无效", false, null, null));
    }

    private static Failure unavailable() {
        return new Failure(503, new ErrorView("SERVICE_UNAVAILABLE", "运行服务暂不可用", true, null, null));
    }
}
