package com.metricplatform.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.environment.EnvironmentStore;
import com.metricplatform.identity.TenantScope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 环境版本和物理归属在同一事务中变更，沿用现有审计 Store 的参数化 SQL。 */
@Repository
public class ExecutionEnvironmentStore implements EnvironmentStore {
    private static final String INSERT =
            "insert into execution_environment(tenant_id,environment_id,name,config_version)"
                    + " values(?,?,?,1)";
    private static final String UPDATE =
            "update execution_environment set name=?,config_version=?,updated_at=CURRENT_TIMESTAMP"
                    + " where tenant_id=? and environment_id=?";
    private static final String LOCK =
            "select config_version from execution_environment where tenant_id=? and"
                    + " environment_id=? for update";
    private static final String VERSION =
            "insert into"
                + " execution_environment_version(tenant_id,environment_id,config_version,execution)"
                + " values(?,?,?,?::jsonb)";
    private static final String RESERVE =
            "insert into"
                + " execution_namespace_owner(tenant_id,instance_id,adapter_type,catalog_name,schema_name,environment_id)"
                + " values(?,?,?,?,?,?) on conflict do nothing";
    private static final String OWNER =
            "select exists(select 1 from execution_namespace_owner where tenant_id=? and"
                + " environment_id=? and instance_id=? and adapter_type=? and catalog_name=? and"
                + " schema_name=?)";
    private static final String READ =
            "select e.environment_id,e.name,e.config_version,v.execution::text from"
                    + " execution_environment e join execution_environment_version v"
                    + " using(tenant_id,environment_id,config_version)";
    private static final String TENANT_FILTER = " where e.tenant_id=?";
    private static final String BY_ID = " where e.tenant_id=? and e.environment_id=?",
            ORDER = " order by e.created_at,e.environment_id";

    /** 平台持久连接。 */
    private final JdbcTemplate jdbc;

    /** 不可变快照的 JSON 映射。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionEnvironmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public EnvironmentView create(String tenant, EnvironmentInput input) {
        TenantScope.require(tenant);
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT, tenant, id, input.name());
        return save(tenant, id, 1, input);
    }

    @Override
    @Transactional
    public EnvironmentView update(
            String tenant, UUID id, long expectedVersion, EnvironmentInput input) {
        TenantScope.require(tenant);
        var versions = jdbc.query(LOCK, (rs, index) -> rs.getLong(1), tenant, id);
        if (versions.isEmpty()) throw new NoSuchElementException("环境不存在");
        if (versions.getFirst() != expectedVersion) throw conflict("ENVIRONMENT_VERSION_CONFLICT");
        long version = expectedVersion + 1;
        jdbc.update(UPDATE, input.name(), version, tenant, id);
        return save(tenant, id, version, input);
    }

    private EnvironmentView save(String tenant, UUID id, long version, EnvironmentInput input) {
        var connection = input.connection();
        jdbc.update(
                RESERVE,
                tenant,
                connection.instanceId(),
                connection.type(),
                input.catalog(),
                input.schema(),
                id);
        Boolean ownsTarget =
                jdbc.queryForObject(
                        OWNER,
                        Boolean.class,
                        tenant,
                        id,
                        connection.instanceId(),
                        connection.type(),
                        input.catalog(),
                        input.schema());
        if (!Boolean.TRUE.equals(ownsTarget)) throw conflict("EXECUTION_TARGET_CONFLICT");
        var snapshot = input.snapshot(id, version);
        try {
            jdbc.update(VERSION, tenant, id, version, mapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("环境配置无法保存", error);
        }
        return new EnvironmentView(id, input.name(), version, snapshot);
    }

    @Override
    public EnvironmentView get(String tenant, UUID id) {
        TenantScope.require(tenant);
        var rows = jdbc.query(READ + BY_ID, this::read, tenant, id);
        if (rows.isEmpty()) throw new NoSuchElementException("环境不存在");
        return rows.getFirst();
    }

    @Override
    public List<EnvironmentView> list(String tenant) {
        TenantScope.require(tenant);
        return jdbc.query(READ + TENANT_FILTER + ORDER, this::read, tenant);
    }

    private EnvironmentView read(ResultSet row, int index) throws SQLException {
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
