package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.EventEntity;
import com.metricplatform.persistence.mapper.AuditEventMapper;
import org.springframework.stereotype.Service;

@Service
public class AuditEventPersistenceService extends ServiceImpl<AuditEventMapper, EventEntity> {}
