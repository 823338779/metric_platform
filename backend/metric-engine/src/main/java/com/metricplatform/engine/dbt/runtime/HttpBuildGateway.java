package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 唯一 v3 HTTP 适配；不持有部署指针，不重解释构建的业务时区。 */
@Component
public class HttpBuildGateway implements BuildGateway {
    private static final String ROOT = "/v3",
            BUILDS = ROOT + "/builds",
            DEPLOYMENTS = ROOT + "/deployments",
            QUERIES = ROOT + "/queries",
            OPTIONS = ROOT + "/query-options";
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
            ENVIRONMENT = "environment",
            CURSOR = "cursor",
            LIMIT = "limit",
            Q = "q",
            KIND = "kind",
            OFFSET = "offset",
            DETAIL = "detail",
            ERROR = "error",
            CODE = "code",
            ERROR_MESSAGE = "message",
            RETRYABLE = "retryable",
            LOCATION = "loc",
            MESSAGE = "msg",
            ERROR_TYPE = "type",
            REQUEST_VALIDATION_ERROR = "REQUEST_VALIDATION_ERROR";
    private static final Set<String> VIEWS = Set.of(EMPTY, "source", "lineage", "native-details");
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** 固定运行服务地址。 */
    private final URI baseUri;

    /** 每次请求的最大等待时间。 */
    private final Duration timeout;

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
        this(properties.getBaseUri(), properties.getTimeout(), properties.getServiceToken());
    }

    public HttpBuildGateway(URI uri, Duration timeout, String token) {
        if (uri == null || !Set.of("http", "https").contains(uri.getScheme()))
            throw new IllegalArgumentException("运行服务地址无效");
        this.baseUri = uri;
        this.timeout = timeout;
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
        return call(
                GET,
                BUILDS
                        + parameters(
                                REPOSITORY,
                                f.repository(),
                                BRANCH,
                                f.branchName(),
                                CURSOR,
                                f.cursor(),
                                LIMIT,
                                f.limit()),
                null,
                BuildPage.class);
    }

    public DeploymentTargetView current(DeploymentKey k) {
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
                                k.environment()),
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
                                f.environment(),
                                CURSOR,
                                f.cursor(),
                                LIMIT,
                                f.limit()),
                null,
                DeploymentPage.class);
    }

    public CatalogPage catalog(UUID id, CatalogFilter f) {
        return scoped(
                id,
                call(
                        GET,
                        BUILDS
                                + SLASH
                                + id
                                + "/catalog"
                                + parameters(
                                        Q,
                                        f.q(),
                                        KIND,
                                        f.kind(),
                                        CURSOR,
                                        f.cursor(),
                                        LIMIT,
                                        f.limit()),
                        null,
                        CatalogPage.class));
    }

    public ResourceView resource(UUID id, String resource, String view) {
        if (!VIEWS.contains(view)) throw new IllegalArgumentException("资源视图无效");
        return scoped(
                id,
                call(
                        GET,
                        BUILDS
                                + SLASH
                                + id
                                + "/resources/"
                                + encode(resource)
                                + (view.isEmpty() ? EMPTY : SLASH + view),
                        null,
                        ResourceView.class));
    }

    public OptionsTaskView options(UUID id, OptionsRequest request) {
        return scoped(
                id,
                call(POST, BUILDS + SLASH + id + "/query-options", request, OptionsTaskView.class));
    }

    public OptionsTaskView getOptions(UUID id) {
        var result = call(GET, OPTIONS + SLASH + id, null, OptionsTaskView.class);
        if (!id.equals(result.optionsTaskId())) throw invalid();
        return result;
    }

    public QueryView query(UUID id, QueryRequest request) {
        return scoped(id, call(POST, BUILDS + SLASH + id + "/queries", request, QueryView.class));
    }

    public QueryView queryStatus(UUID id) {
        var result = call(GET, QUERIES + SLASH + id + "/status", null, QueryView.class);
        if (!id.equals(result.queryId())) throw invalid();
        return result;
    }

    public ResultPage queryResults(UUID id, int offset, int limit) {
        var result =
                call(
                        GET,
                        QUERIES
                                + SLASH
                                + id
                                + "/results"
                                + parameters(OFFSET, offset, LIMIT, limit),
                        null,
                        ResultPage.class);
        if (!id.equals(result.queryId())) throw invalid();
        return result;
    }

    public ChangePage changes(String cursor, int limit) {
        return call(
                GET,
                ROOT + "/changes" + parameters(CURSOR, cursor, LIMIT, limit),
                null,
                ChangePage.class);
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

    private <T> T call(String method, String path, Object body, Class<T> type) {
        try {
            var request =
                    HttpRequest.newBuilder(baseUri.resolve(path))
                            .timeout(timeout)
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
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw invalid();
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
                if (POST.equals(method) && response.statusCode() != 202) throw invalid();
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
                    case CatalogPage v -> v.buildId();
                    case ResourceView v -> v.buildId();
                    case OptionsTaskView v -> v.buildId();
                    case QueryView v -> v.buildId();
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
