package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DimensionEntity;
import com.metricplatform.persistence.mapper.DimensionMapper;
import org.springframework.stereotype.Service;

@Service
public class DimensionEntityPersistenceService
        extends ServiceImpl<DimensionMapper, DimensionEntity> {
    public boolean softDeleteById(Object id) {
        return baseMapper.softDeleteById((java.io.Serializable) id) > 0;
    }
}
