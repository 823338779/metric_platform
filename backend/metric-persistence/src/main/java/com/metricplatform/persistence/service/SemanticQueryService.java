package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticQueryEntity;
import com.metricplatform.persistence.mapper.SemanticQueryMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 查询任务常规读写使用 IService，受理时复用发布版本行锁。 */
@Service
public class SemanticQueryService
        extends ServiceImpl<SemanticQueryMapper, SemanticQueryEntity> {
    public Map<String, Object> lockRelease(UUID releaseId, String projectId) {
        return baseMapper.lockRelease(releaseId, projectId);
    }
}
