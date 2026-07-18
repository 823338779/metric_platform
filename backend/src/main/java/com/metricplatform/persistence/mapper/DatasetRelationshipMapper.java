package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasetRelationshipMapper
        extends AuditableBaseMapper<DatasetRelationshipEntity> {}
