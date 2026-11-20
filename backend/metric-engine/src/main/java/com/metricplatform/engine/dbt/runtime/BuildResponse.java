package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.ExecutionSnapshot;
import java.util.UUID;

final class BuildResponse {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BuildResponse() {}

    static ExecutionSnapshot execution(JsonNode build) {
        // 构建读取与提交共用完整快照校验，不接受只有 environmentId 的局部响应。
        try {
            ExecutionSnapshot snapshot =
                    JSON.treeToValue(build.path("execution"), ExecutionSnapshot.class);
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
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    static void requireEnvironment(JsonNode build, UUID environmentId) {
        if (!environmentId.equals(execution(build).environmentId())) throw invalid();
    }

    static BuildGateway.Failure invalid() {
        return new BuildGateway.Failure(
                502,
                new ErrorView("INVALID_SERVICE_RESPONSE", "服务返回构建身份或环境不匹配", false, null, null));
    }
}
