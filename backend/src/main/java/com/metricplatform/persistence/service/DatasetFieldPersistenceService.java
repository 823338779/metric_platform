package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import org.springframework.stereotype.Service;

@Service
public class DatasetFieldPersistenceService
        extends ServiceImpl<DatasetFieldMapper, DatasetFieldEntity> {
    public boolean softDeleteById(Object id) {
        return baseMapper.softDeleteById((java.io.Serializable) id) > 0;
    }
}
