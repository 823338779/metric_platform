package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.MetricEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricMapper extends AuditableBaseMapper<MetricEntity> {}
