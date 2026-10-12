package com.metricplatform.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.*;
import com.metricplatform.environment.EnvironmentContracts.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** HTTP 调用前完成短事务，确保响应丢失后可重发相同请求。 */
@Repository
public class EnvironmentBuildSubmissionStore implements BuildSubmissionStore {
    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(?,0))";
    private static final String READ =
            "select environment_id,command::text,request::text from environment_build_submission"
                + " where actor_id=? and idempotency_key=?";
    private static final String LOCK_ENV =
            "select config_version from execution_environment where environment_id=? for update";
    private static final String INSERT =
            "insert into"
                + " environment_build_submission(actor_id,idempotency_key,environment_id,command,request)"
                + " values(?,?,?,?::jsonb,?::jsonb)";
    private static final String ACCEPTED =
            "update environment_build_submission set build_id=? where actor_id=? and"
                + " idempotency_key=? and (build_id is null or build_id=?)";
    private static final String SEPARATOR = ":";

    /** 平台持久事务。 */
    private final JdbcTemplate jdbc;

    /** 当前环境版本端口。 */
    private final EnvironmentStore environments;

    /** 固定请求序列化。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public EnvironmentBuildSubmissionStore(JdbcTemplate jdbc, EnvironmentStore environments) {
        this.jdbc = jdbc;
        this.environments = environments;
    }

    @Override
    @Transactional
    public SnapshotBuildRequest reserve(UUID actor, UUID environment, SubmitBuildCommand command) {
        String scope = actor + SEPARATOR + command.idempotencyKey();
        jdbc.query(LOCK, rs -> {}, scope);
        String original = json(command);
        var previous =
                jdbc.query(
                        READ,
                        (row, index) -> {
                            if (!environment.equals(row.getObject(1, UUID.class))
                                    || !tree(original).equals(tree(row.getString(2)))) {
                                throw conflict("IDEMPOTENCY_CONFLICT");
                            }
                            try {
                                return mapper.readValue(
                                        row.getString(3), SnapshotBuildRequest.class);
                            } catch (Exception error) {
                                throw new IllegalStateException("构建请求无法读取", error);
                            }
                        },
                        actor,
                        command.idempotencyKey());
        if (!previous.isEmpty()) return previous.getFirst();
        jdbc.query(LOCK_ENV, rs -> {}, environment);
        var selected = environments.get(environment);
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
        jdbc.update(INSERT, actor, command.idempotencyKey(), environment, original, json(request));
        return request;
    }

    @Override
    public void accepted(UUID actor, String key, UUID buildId) {
        if (jdbc.update(ACCEPTED, buildId, actor, key, buildId) != 1)
            throw conflict("IDEMPOTENCY_CONFLICT");
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
