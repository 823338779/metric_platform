package com.metricplatform.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.*;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.mapper.EnvironmentBuildSubmissionMapper;
import com.metricplatform.persistence.mapper.ExecutionEnvironmentMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** HTTP 调用前完成短事务，确保响应丢失后可重发相同请求。 */
@Repository
public class EnvironmentBuildSubmissionStore implements BuildSubmissionStore {
    private final EnvironmentBuildSubmissionMapper submissions;
    private final ExecutionEnvironmentMapper environmentRows;
    private final EnvironmentStore environments;
    private final ObjectMapper mapper = new ObjectMapper();

    public EnvironmentBuildSubmissionStore(
            EnvironmentBuildSubmissionMapper submissions,
            ExecutionEnvironmentMapper environmentRows,
            EnvironmentStore environments) {
        this.submissions = submissions;
        this.environmentRows = environmentRows;
        this.environments = environments;
    }

    @Override
    @Transactional
    public SnapshotBuildRequest reserve(
            String tenant, UUID actor, UUID environment, SubmitBuildCommand command) {
        TenantScope.require(tenant);
        String scope = actor + ":" + command.idempotencyKey();
        submissions.lockIdempotencyKey(scope);
        String original = json(command);
        var previous = submissions.find(actor, command.idempotencyKey());
        if (previous != null) {
            if (!environment.equals(previous.environmentId())
                    || !tree(original).equals(tree(previous.command()))) {
                throw conflict("IDEMPOTENCY_CONFLICT");
            }
            try {
                return mapper.readValue(previous.request(), SnapshotBuildRequest.class);
            } catch (Exception error) {
                throw new IllegalStateException("构建请求无法读取", error);
            }
        }
        environmentRows.lockVersion(environment);
        var selected = environments.get(tenant, environment);
        if (selected.configVersion() != command.expectedConfigVersion())
            throw conflict("ENVIRONMENT_VERSION_CONFLICT");
        String key = UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString();
        var request =
                new SnapshotBuildRequest(
                        command.repository(),
                        command.branchName(),
                        command.commitSha(),
                        selected.execution(),
                        command.deploymentPolicy(),
                        key);
        submissions.insert(actor, command.idempotencyKey(), environment, original, json(request));
        return request;
    }

    @Override
    @Transactional
    public void accepted(String tenant, UUID actor, String key, UUID buildId) {
        TenantScope.require(tenant);
        // 与提交确认原子绑定，重复确认须匹配环境及租户。
        if (submissions.accept(actor, key, buildId) != 1) throw conflict("IDEMPOTENCY_CONFLICT");
        if (submissions.bindOwnership(tenant, actor, key) != 1)
            throw conflict("BUILD_OWNERSHIP_CONFLICT");
        if (submissions.bindTarget(tenant, actor, key) != 1)
            throw conflict("BUILD_OWNERSHIP_CONFLICT");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("构建请求无法保存", error);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("构建请求无法读取", error);
        }
    }

    private static BuildGateway.Failure conflict(String code) {
        return new BuildGateway.Failure(
                409, new ErrorView(code, "构建输入或环境配置版本发生冲突", false, null, null));
    }
}
