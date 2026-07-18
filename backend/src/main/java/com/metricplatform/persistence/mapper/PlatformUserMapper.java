package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformUserMapper extends AuditableBaseMapper<PlatformUserEntity> {}
