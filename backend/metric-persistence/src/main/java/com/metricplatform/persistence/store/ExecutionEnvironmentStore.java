package com.metricplatform.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.environment.EnvironmentStore;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.ExecutionEnvironmentEntity;
import com.metricplatform.persistence.mapper.ExecutionEnvironmentMapper;
import com.metricplatform.persistence.mapper.ExecutionEnvironmentMapper.EnvironmentRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 环境版本和物理归属在同一事务中变更。 */
@Repository
public class ExecutionEnvironmentStore implements EnvironmentStore {
    private final ExecutionEnvironmentMapper environments;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionEnvironmentStore(ExecutionEnvironmentMapper environments) {
        this.environments = environments;
    }

    @Override
    @Transactional
    public EnvironmentView create(String tenant, EnvironmentInput input) {
        TenantScope.require(tenant);
        UUID id = UUID.randomUUID();
        environments.insert(new ExecutionEnvironmentEntity(id, input.name(), 1));
        return save(id, 1, input);
    }

    @Override
    @Transactional
    public EnvironmentView update(
            String tenant, UUID id, long expectedVersion, EnvironmentInput input) {
        TenantScope.require(tenant);
        Long current = environments.lockVersion(id);
        if (current == null) throw new NoSuchElementException("环境不存在");
        if (current != expectedVersion) throw conflict("ENVIRONMENT_VERSION_CONFLICT");
        long version = expectedVersion + 1;
        environments.updateById(new ExecutionEnvironmentEntity(id, input.name(), version));
        return save(id, version, input);
    }

    private EnvironmentView save(UUID id, long version, EnvironmentInput input) {
        environments.reserveNamespace(id, input);
        if (!environments.ownsNamespace(id, input)) throw conflict("EXECUTION_TARGET_CONFLICT");
        var snapshot = input.snapshot(id, version);
        try {
            environments.insertVersion(id, version, mapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("环境配置无法保存", error);
        }
        return new EnvironmentView(id, input.name(), version, snapshot);
    }

    @Override
    public EnvironmentView get(String tenant, UUID id) {
        TenantScope.require(tenant);
        var row = environments.find(id);
        if (row == null) throw new NoSuchElementException("环境不存在");
        return read(row);
    }

    @Override
    public List<EnvironmentView> list(String tenant) {
        TenantScope.require(tenant);
        return environments.list().stream().map(this::read).toList();
    }

    private EnvironmentView read(EnvironmentRow row) {
        try {
            return new EnvironmentView(
                    row.environmentId(),
                    row.name(),
                    row.configVersion(),
                    mapper.readValue(row.execution(), ExecutionSnapshot.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("环境配置无法读取", error);
        }
    }

    private static BuildGateway.Failure conflict(String code) {
        return new BuildGateway.Failure(
                409, new ErrorView(code, "环境配置版本或物理目标冲突", false, null, null));
    }
}
