package com.metricplatform.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.AgentProjectContracts.*;
import com.metricplatform.catalog.AgentProjectGateway;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 通过 Agent 公开接口读取项目配置与真实 Git 分支。 */
@Component
public class HttpAgentProjectGateway implements AgentProjectGateway {
    private static final String ROOT = "/api/v1/agent-projects",
            AUTHORIZATION = "Authorization",
            BEARER = "Bearer ",
            AGENT = "AGENT";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    /** 部署固定的上游服务地址。 */
    private final URI baseUri;

    /** HTTP 请求超时时间。 */
    private final Duration timeout;

    /** 部署提供的服务访问凭据。 */
    private final String token;

    /** 禁止自动重定向的 HTTP 客户端。 */
    private final HttpClient client;

    /** 负责封存 JSON 的类型映射。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public HttpAgentProjectGateway(
            @Value("${metric-platform.agent.base-uri:http://127.0.0.1:8080}") URI uri,
            @Value("${metric-platform.agent.timeout:PT30S}") Duration timeout,
            @Value("${metric-platform.agent.service-token:}") String token) {
        this.baseUri = uri;
        this.timeout = timeout;
        this.token = token;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public ProjectPage listProjects(String cursor, int limit) {
        return get(
                ROOT + "?limit=" + limit + (cursor == null ? "" : "&cursor=" + encode(cursor)),
                ProjectPage.class);
    }

    public BranchList listBranches(String id) {
        var page = get(ROOT + "/" + encode(id) + "/branches", BranchPage.class);
        return new BranchList(page.items(), Instant.now().toString(), false, AGENT);
    }

    private record BranchPage(List<BranchView> items) {}

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private <T> T get(String path, Class<T> type) {
        try {
            var request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout).GET();
            if (token != null && !token.isBlank()) request.header(AUTHORIZATION, BEARER + token);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw failure(response.statusCode());
                var bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw failure(502);
                return mapper.readValue(bytes, type);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(503);
        } catch (java.io.IOException e) {
            throw failure(503);
        }
    }

    private static BuildGateway.Failure failure(int status) {
        return new BuildGateway.Failure(
                status,
                new ErrorView("AGENT_UNAVAILABLE", "Agent 项目或分支不可用", status >= 500, null, null));
    }
}
