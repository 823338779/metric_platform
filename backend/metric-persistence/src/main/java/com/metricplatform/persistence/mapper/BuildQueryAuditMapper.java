package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.QueryExecutionAuditEntity;
import com.metricplatform.query.QueryAuditRepository;

import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface BuildQueryAuditMapper
        extends BaseMapper<QueryExecutionAuditEntity>, QueryAuditRepository {
    @Override
    default void record(UUID execution, UUID build, UUID user) {
        var audit = new QueryExecutionAuditEntity();
        audit.setExecutionId(execution);
        audit.setBuildId(build);
        audit.setUserId(user);
        insert(audit);
    }
}
