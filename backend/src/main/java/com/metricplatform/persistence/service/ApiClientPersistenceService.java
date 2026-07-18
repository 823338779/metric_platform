package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.ApiClientEntity;
import com.metricplatform.persistence.mapper.ApiClientMapper;
import org.springframework.stereotype.Service;

@Service
public class ApiClientPersistenceService extends ServiceImpl<ApiClientMapper, ApiClientEntity> {}
