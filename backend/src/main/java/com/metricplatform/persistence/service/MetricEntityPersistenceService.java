package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.MetricEntity;
import com.metricplatform.persistence.mapper.MetricMapper;
import org.springframework.stereotype.Service;

@Service
public class MetricEntityPersistenceService
        extends ServiceImpl<MetricMapper, MetricEntity> {
    public boolean softDeleteById(Object id) {
        return baseMapper.softDeleteById((java.io.Serializable) id) > 0;
    }
}
