package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.mapper.PlatformUserMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserPersistenceService
        extends ServiceImpl<PlatformUserMapper, PlatformUserEntity> {}
