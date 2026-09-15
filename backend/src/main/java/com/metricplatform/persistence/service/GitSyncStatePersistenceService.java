package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.GitSyncStateEntity;
import com.metricplatform.persistence.mapper.GitSyncStateMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 仅提供检查点锁和事务内写授权，业务事务由发布层控制。 */
@Service
public class GitSyncStatePersistenceService
        extends ServiceImpl<GitSyncStateMapper, GitSyncStateEntity> {
    public GitSyncStateEntity initializeAndLock(
            String repositoryId, String modelName, UUID dataSourceId, String schemaName) {
        baseMapper.initialize(repositoryId, modelName, dataSourceId, schemaName);
        return baseMapper.lock(repositoryId);
    }

    public void authorizePublication(String repositoryId) {
        baseMapper.authorize(repositoryId);
    }
}
