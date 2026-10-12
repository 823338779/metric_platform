package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 复用有界 HTTP 传输，v4 仅承载环境快照构建，不回退旧绑定。 */
@Component
public class HttpEnvironmentBuildGateway implements EnvironmentBuildGateway {
    private static final String BUILDS = "/v4/builds", GET = "GET", POST = "POST", SLASH = "/";
    private static final String BUILD_ID = "buildId",
            EXECUTION = "execution",
            ENVIRONMENT_ID = "environmentId",
            ITEMS = "items";

    /** 与既有查询门面共用服务认证和响应大小边界。 */
    private final HttpBuildGateway transport;

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
        return value;
    }

    @Override
    public JsonNode logs(UUID id, String cursor) {
        var query = new StringJoiner("&", "?", "");
        add(query, "cursor", cursor);
        return transport.call(GET, BUILDS + SLASH + id + "/logs" + query, null, JsonNode.class);
    }

    private static void verifyEnvironment(JsonNode value, UUID id) {
        if (!id.toString().equals(value.path(EXECUTION).path(ENVIRONMENT_ID).asText()))
            throw invalid();
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
