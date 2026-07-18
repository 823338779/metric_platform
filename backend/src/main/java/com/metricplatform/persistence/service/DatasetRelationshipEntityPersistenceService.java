package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DatasetRelationshipEntity;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import org.springframework.stereotype.Service;

@Service
public class DatasetRelationshipEntityPersistenceService
        extends ServiceImpl<DatasetRelationshipMapper, DatasetRelationshipEntity> {}
