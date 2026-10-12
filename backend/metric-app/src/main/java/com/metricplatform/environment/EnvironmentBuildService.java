package com.metricplatform.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.environment.EnvironmentContracts.*;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 固定请求持久化后再调用服务，平台不执行 Git 或 dbt。 */
@Service
public class EnvironmentBuildService {
    private static final String BUILD_ID = "buildId";
    private static final String SSH = "ssh", PASSWORD_SEPARATOR = ":";
    private static final Set<String> POLICIES = Set.of("NONE", "ON_SUCCESS"),
            SCHEMES = Set.of("http", "https", "ssh");

    /** 提交幂等记录。 */
    private final BuildSubmissionStore submissions;

    /** 服务公开构建接口。 */
    private final EnvironmentBuildGateway gateway;

    public EnvironmentBuildService(
            BuildSubmissionStore submissions, EnvironmentBuildGateway gateway) {
        this.submissions = submissions;
        this.gateway = gateway;
    }

    public JsonNode submit(UUID environment, SubmitBuildCommand command, UUID actor) {
        validate(command);
        var fixed = submissions.reserve(actor, environment, command);
        JsonNode result = gateway.submit(fixed);
        submissions.accepted(
                actor, command.idempotencyKey(), UUID.fromString(result.path(BUILD_ID).asText()));
        return result;
    }

    private void validate(SubmitBuildCommand command) {
        if (command.repository() == null
                || command.repository().length() > 2048
                || command.branchName() == null
                || command.branchName().isBlank()
                || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 256
                || command.expectedConfigVersion() < 1
                || command.deploymentPolicy() == null
                || !POLICIES.contains(command.deploymentPolicy())) {
            throw new IllegalArgumentException("仓库、分支、环境版本和提交键为必填项");
        }
        URI repository = URI.create(command.repository());
        if (repository.getScheme() == null
                || !SCHEMES.contains(repository.getScheme())
                || repository.getHost() == null
                || repository.getQuery() != null
                || repository.getFragment() != null
                || repository.getUserInfo() != null
                        && (!SSH.equals(repository.getScheme())
                                || repository.getUserInfo().contains(PASSWORD_SEPARATOR)))
            throw new IllegalArgumentException("仓库地址不能包含凭据或查询参数");
    }
}
