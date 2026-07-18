package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformTenantEntity;
import com.metricplatform.persistence.mapper.PlatformTenantMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformTenantPersistenceService
        extends ServiceImpl<PlatformTenantMapper, PlatformTenantEntity> {}
