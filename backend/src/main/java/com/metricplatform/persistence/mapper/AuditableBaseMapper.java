package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.BaseAuditEntity;
import java.io.Serializable;

public interface AuditableBaseMapper<T extends BaseAuditEntity> extends BaseMapper<T> {
    default int softDeleteById(Serializable id) {
        T entity = selectById(id);
        if (entity == null) {
            return 0;
        }
        entity.setDeleted(true);
        return deleteById(entity, true);
    }
}
