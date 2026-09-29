package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformAccessTokenMapper extends AuditableBaseMapper<PlatformAccessTokenEntity> {}
