package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.mapper.PlatformAccessTokenMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformAccessTokenPersistenceService
        extends ServiceImpl<PlatformAccessTokenMapper, PlatformAccessTokenEntity> {}
