package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/** 复用有界 HTTP 传输，完整环境快照构建，不回退旧绑定。 */
@Component
public class HttpEnvironmentBuildGateway implements EnvironmentBuildGateway {
    private static final String BUILDS = "/builds", GET = "GET", POST = "POST", SLASH = "/";
    private static final String BUILD_ID = "buildId",
            EXECUTION = "execution",
            ENVIRONMENT_ID = "environmentId",
            ITEMS = "items";
    private static final String REPOSITORY = "repository",
            BRANCH = "branchName",
            REQUESTED_COMMIT = "requestedCommitSha",
            COMMIT = "commitSha",
            STATUS = "buildStatus";

    /** 与既有查询门面共用服务认证和响应大小边界。 */
    private final HttpBuildGateway transport;

    /** 按完整契约解析执行快照，拒绝同环境下错误版本或物理目标的回执。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    public HttpEnvironmentBuildGateway(DbtRuntimeProperties properties) {
        this(new HttpBuildGateway(properties));
    }

    public HttpEnvironmentBuildGateway(HttpBuildGateway transport) {
        this.transport = transport;
    }

    @Override
    public JsonNode submit(SnapshotBuildRequest request) {
        JsonNode value = transport.call(POST, BUILDS, request, JsonNode.class);
        verifyEnvironment(value, request.execution().environmentId());
        if (!request.execution().equals(execution(value))) throw invalid();
        // 受理回执必须属于同一源码请求，避免将错误构建固定到本次提交。
        if (!Objects.equals(request.repository(), value.path(REPOSITORY).asText(null))
                || !Objects.equals(request.branchName(), value.path(BRANCH).asText(null))
                || !Objects.equals(request.commitSha(), value.path(REQUESTED_COMMIT).asText(null)))
            throw invalid();
        String resolved = value.path(COMMIT).asText(null);
        if (request.commitSha() != null
                && resolved != null
                && !request.commitSha().equals(resolved)) throw invalid();
        try {
            com.metricplatform.catalog.BuildContracts.BuildStatus.valueOf(
                    value.path(STATUS).asText());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        UUID.fromString(value.path(BUILD_ID).asText());
        return value;
    }

    @Override
    public JsonNode history(EnvironmentBuildFilter filter) {
        var query = new StringJoiner("&", "?", "");
        add(query, ENVIRONMENT_ID, filter.environmentId().toString());
        add(query, "repository", filter.repository());
        add(query, "branchName", filter.branchName());
        add(query, "buildStatus", filter.buildStatus());
        add(query, "cursor", filter.cursor());
        add(query, "limit", Integer.toString(filter.limit()));
        JsonNode page = transport.call(GET, BUILDS + query, null, JsonNode.class);
        if (!page.path(ITEMS).isArray()) throw invalid();
        for (JsonNode value : page.path(ITEMS)) verifyEnvironment(value, filter.environmentId());
        return page;
    }

    @Override
    public JsonNode getBuild(UUID id) {
        JsonNode value = transport.call(GET, BUILDS + SLASH + id, null, JsonNode.class);
        if (!id.toString().equals(value.path(BUILD_ID).asText())) throw invalid();
        execution(value);
        return value;
    }

    @Override
    public JsonNode logs(UUID id, String cursor) {
        var query = new StringJoiner("&", "?", "");
        add(query, "cursor", cursor);
        return transport.call(GET, BUILDS + SLASH + id + "/logs" + query, null, JsonNode.class);
    }

    private static void verifyEnvironment(JsonNode value, UUID id) {
        if (!id.equals(execution(value).environmentId())) throw invalid();
    }

    private static ExecutionSnapshot execution(JsonNode value) {
        // 构建读取与提交共用完整快照校验，不接受只有 environmentId 的局部响应。
        try {
            ExecutionSnapshot snapshot =
                    JSON.treeToValue(value.path(EXECUTION), ExecutionSnapshot.class);
            if (snapshot == null
                    || snapshot.environmentId() == null
                    || snapshot.configVersion() < 1
                    || snapshot.connection() == null
                    || snapshot.connection().type() == null
                    || snapshot.connection().instanceId() == null
                    || snapshot.credentialRef() == null
                    || snapshot.catalog() == null
                    || snapshot.schema() == null
                    || snapshot.threads() < 1
                    || snapshot.businessTimezone() == null) throw invalid();
            return snapshot;
        } catch (com.fasterxml.jackson.core.JsonProcessingException
                | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void add(StringJoiner query, String key, String value) {
        if (value != null && !value.isBlank())
            query.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static BuildGateway.Failure invalid() {
        return new BuildGateway.Failure(
                502,
                new ErrorView("INVALID_SERVICE_RESPONSE", "服务返回构建身份或环境不匹配", false, null, null));
    }
}
