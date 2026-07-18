package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.ApiClientEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiClientMapper extends AuditableBaseMapper<ApiClientEntity> {}
