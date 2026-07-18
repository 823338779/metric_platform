package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformWorkspaceMapper extends AuditableBaseMapper<PlatformWorkspaceEntity> {}
