package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticResourceEntity;
import com.metricplatform.persistence.mapper.SemanticResourceMapper;
import org.springframework.stereotype.Service;

/** 已发布 语义资源的 IService 持久化入口。 */
@Service
public class SemanticResourceService
        extends ServiceImpl<SemanticResourceMapper, SemanticResourceEntity> {}
