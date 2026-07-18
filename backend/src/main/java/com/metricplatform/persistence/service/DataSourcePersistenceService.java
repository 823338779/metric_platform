package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DataSourcePersistenceService
        extends ServiceImpl<DataSourceMapper, DataSourceEntity> {
    public List<DataSourceEntity> listIncludingDeleted() {
        return baseMapper.selectAllIncludingDeleted();
    }

    public boolean softDeleteById(Object id) {
        return baseMapper.softDeleteById((java.io.Serializable) id) > 0;
    }
}
