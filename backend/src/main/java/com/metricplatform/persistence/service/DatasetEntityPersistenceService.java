package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.mapper.DatasetMapper;
import org.springframework.stereotype.Service;

@Service
public class DatasetEntityPersistenceService
        extends ServiceImpl<DatasetMapper, DatasetEntity> {
    public boolean softDeleteById(Object id) {
        return baseMapper.softDeleteById((java.io.Serializable) id) > 0;
    }
}
