package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticDependencyEntity;
import com.metricplatform.persistence.mapper.SemanticDependencyMapper;
import org.springframework.stereotype.Service;

/** 已发布 资源血缘的 IService 持久化入口。 */
@Service
public class SemanticDependencyService
        extends ServiceImpl<SemanticDependencyMapper, SemanticDependencyEntity> {}
