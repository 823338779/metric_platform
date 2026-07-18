package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformIdentityDatasetGrantEntity;
import com.metricplatform.persistence.mapper.PlatformIdentityDatasetGrantMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlatformIdentityDatasetGrantPersistenceService
        extends ServiceImpl<PlatformIdentityDatasetGrantMapper, PlatformIdentityDatasetGrantEntity> {
    public void physicallyDeleteByIdentityId(UUID identityId) {
        baseMapper.physicallyDeleteByIdentityId(identityId);
    }
}
