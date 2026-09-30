package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import com.metricplatform.persistence.mapper.SemanticReleaseMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 发布版本常规读写使用 IService，并集中封装并发控制所需的行锁。 */
@Service
public class SemanticReleaseService
        extends ServiceImpl<SemanticReleaseMapper, SemanticReleaseEntity> {
    public SemanticReleaseEntity lockRelease(UUID releaseId) {
        return baseMapper.lockRelease(releaseId);
    }

}
