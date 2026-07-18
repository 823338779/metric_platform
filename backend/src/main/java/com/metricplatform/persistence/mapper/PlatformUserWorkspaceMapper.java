package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformUserWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformUserWorkspaceMapper
        extends AuditableBaseMapper<PlatformUserWorkspaceEntity> {}
