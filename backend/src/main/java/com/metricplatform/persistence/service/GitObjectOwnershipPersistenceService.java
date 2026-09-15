package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.gitsync.GitObjectKind;
import com.metricplatform.persistence.entity.GitObjectOwnershipEntity;
import com.metricplatform.persistence.mapper.GitObjectOwnershipMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 归属不随软删除丢失，恢复 SQL 的表名严格由枚举控制。 */
@Service
public class GitObjectOwnershipPersistenceService
        extends ServiceImpl<GitObjectOwnershipMapper, GitObjectOwnershipEntity> {
    public void restore(GitObjectKind kind, UUID id) {
        baseMapper.restore(kind.table(), id);
    }

    public boolean hasExternalReferences(UUID id, String repositoryId) {
        return baseMapper.externalReferences(id, repositoryId) > 0;
    }
}
