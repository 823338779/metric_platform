package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DimensionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DimensionMapper extends AuditableBaseMapper<DimensionEntity> {}
