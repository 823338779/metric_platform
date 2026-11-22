package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.catalog.BuildContracts.BuildFilter;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 复用有界 HTTP 传输，完整环境快照构建，不回退旧绑定。 */
@Component
public class HttpEnvironmentBuildGateway implements EnvironmentBuildGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUILDS = "/builds", GET = "GET", POST = "POST", SLASH = "/";
    private static final String EXECUTION_LOGS = "/execution-logs", LIMIT = "limit";
    private static final String BUILD_ID = "buildId";
    private static final String REPOSITORY = "repository",
            BRANCH = "branchName",
            REQUESTED_COMMIT = "requestedCommitSha",
            COMMIT = "commitSha",
            STATUS = "buildStatus";

    /** 与既有查询门面共用服务认证和响应大小边界。 */
    private final HttpBuildGateway transport;

    public HttpEnvironmentBuildGateway(HttpBuildGateway transport) {
        this.transport = transport;
    }

    @Override
    public JsonNode submit(SnapshotBuildRequest request) {
        var execution = request.execution().forBuild();
        ObjectNode body = JSON.valueToTree(request);
        body.set("execution", JSON.valueToTree(execution));
        JsonNode value = transport.call(POST, BUILDS, body, JsonNode.class);
        if (!execution.equals(BuildResponse.execution(value)))
            throw BuildResponse.invalid();
        // 受理回执必须属于同一源码请求，避免将错误构建固定到本次提交。
        if (!Objects.equals(request.repository(), value.path(REPOSITORY).asText(null))
                || !Objects.equals(request.branchName(), value.path(BRANCH).asText(null))
                || !Objects.equals(request.commitSha(), value.path(REQUESTED_COMMIT).asText(null)))
            throw BuildResponse.invalid();
        String resolved = value.path(COMMIT).asText(null);
        if (request.commitSha() != null
                && resolved != null
                && !request.commitSha().equals(resolved)) throw BuildResponse.invalid();
        try {
            com.metricplatform.catalog.BuildContracts.BuildStatus.valueOf(
                    value.path(STATUS).asText());
        } catch (IllegalArgumentException exception) {
            throw BuildResponse.invalid();
        }
        UUID.fromString(value.path(BUILD_ID).asText());
        return value;
    }

    @Override
    public JsonNode history(BuildFilter filter) {
        return transport.buildHistory(filter);
    }

    @Override
    public JsonNode getBuild(UUID id) {
        JsonNode value = transport.call(GET, BUILDS + SLASH + id, null, JsonNode.class);
        if (!id.toString().equals(value.path(BUILD_ID).asText())) throw BuildResponse.invalid();
        BuildResponse.execution(value);
        return value;
    }

    @Override
    public JsonNode logs(UUID id, String cursor, int limit) {
        var query = new StringJoiner("&", "?", "");
        add(query, "cursor", cursor);
        add(query, LIMIT, Integer.toString(limit));
        return transport.call(
                GET, BUILDS + SLASH + id + EXECUTION_LOGS + query, null, JsonNode.class);
    }

    private static void add(StringJoiner query, String key, String value) {
        if (value != null && !value.isBlank())
            query.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
