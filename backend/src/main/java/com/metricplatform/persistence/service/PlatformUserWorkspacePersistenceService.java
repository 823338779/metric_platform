package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformUserWorkspaceEntity;
import com.metricplatform.persistence.mapper.PlatformUserWorkspaceMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserWorkspacePersistenceService
        extends ServiceImpl<PlatformUserWorkspaceMapper, PlatformUserWorkspaceEntity> {}
