package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformTenantEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformTenantMapper extends AuditableBaseMapper<PlatformTenantEntity> {}
