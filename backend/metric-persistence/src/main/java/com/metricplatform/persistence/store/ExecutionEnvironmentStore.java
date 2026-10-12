package com.metricplatform.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.environment.EnvironmentStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 环境版本和物理归属在同一事务中变更，沿用现有审计 Store 的参数化 SQL。 */
@Repository
public class ExecutionEnvironmentStore implements EnvironmentStore {
    private static final String INSERT =
            "insert into execution_environment(environment_id,name,config_version) values(?,?,1)";
    private static final String UPDATE =
            "update execution_environment set name=?,config_version=?,updated_at=CURRENT_TIMESTAMP"
                + " where environment_id=?";
    private static final String LOCK =
            "select config_version from execution_environment where environment_id=? for update";
    private static final String VERSION =
            "insert into execution_environment_version(environment_id,config_version,execution)"
                + " values(?,?,?::jsonb)";
    private static final String RESERVE =
            "insert into"
                + " execution_namespace_owner(instance_id,adapter_type,catalog_name,schema_name,environment_id)"
                + " values(?,?,?,?,?) on conflict do nothing";
    private static final String OWNER =
            "select environment_id from execution_namespace_owner where instance_id=? and"
                + " adapter_type=? and catalog_name=? and schema_name=?";
    private static final String READ =
            "select e.environment_id,e.name,e.config_version,v.execution::text from"
                + " execution_environment e join execution_environment_version v"
                + " using(environment_id,config_version)";
    private static final String BY_ID = " where e.environment_id=?",
            ORDER = " order by e.created_at,e.environment_id limit 200";

    /** 平台持久连接。 */
    private final JdbcTemplate jdbc;

    /** 不可变快照的 JSON 映射。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionEnvironmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public EnvironmentView create(EnvironmentInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT, id, input.name());
        return save(id, 1, input);
    }

    @Override
    @Transactional
    public EnvironmentView update(UUID id, long expectedVersion, EnvironmentInput input) {
        var versions = jdbc.query(LOCK, (rs, index) -> rs.getLong(1), id);
        if (versions.isEmpty()) throw new NoSuchElementException("环境不存在");
        if (versions.getFirst() != expectedVersion) throw conflict("ENVIRONMENT_VERSION_CONFLICT");
        long version = expectedVersion + 1;
        jdbc.update(UPDATE, input.name(), version, id);
        return save(id, version, input);
    }

    private EnvironmentView save(UUID id, long version, EnvironmentInput input) {
        var connection = input.connection();
        jdbc.update(
                RESERVE,
                connection.instanceId(),
                connection.type(),
                input.catalog(),
                input.schema(),
                id);
        UUID owner =
                jdbc.queryForObject(
                        OWNER,
                        UUID.class,
                        connection.instanceId(),
                        connection.type(),
                        input.catalog(),
                        input.schema());
        if (!id.equals(owner)) throw conflict("EXECUTION_TARGET_CONFLICT");
        var snapshot = input.snapshot(id, version);
        try {
            jdbc.update(VERSION, id, version, mapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("环境配置无法保存", error);
        }
        return new EnvironmentView(id, input.name(), version, snapshot);
    }

    @Override
    public EnvironmentView get(UUID id) {
        var rows = jdbc.query(READ + BY_ID, this::read, id);
        if (rows.isEmpty()) throw new NoSuchElementException("环境不存在");
        return rows.getFirst();
    }

    @Override
    public List<EnvironmentView> list() {
        return jdbc.query(READ + ORDER, this::read);
    }

    private EnvironmentView read(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        try {
            return new EnvironmentView(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    row.getLong(3),
                    mapper.readValue(row.getString(4), ExecutionSnapshot.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("环境配置无法读取", error);
        }
    }

    private static BuildGateway.Failure conflict(String code) {
        return new BuildGateway.Failure(
                409, new ErrorView(code, "环境配置版本或物理目标冲突", false, null, null));
    }
}
