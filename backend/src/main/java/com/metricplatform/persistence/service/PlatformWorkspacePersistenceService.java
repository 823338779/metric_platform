package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformWorkspaceEntity;
import com.metricplatform.persistence.mapper.PlatformWorkspaceMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformWorkspacePersistenceService
        extends ServiceImpl<PlatformWorkspaceMapper, PlatformWorkspaceEntity> {}
