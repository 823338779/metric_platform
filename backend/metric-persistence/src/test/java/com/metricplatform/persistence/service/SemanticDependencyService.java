package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticDependencyEntity;
import com.metricplatform.persistence.mapper.SemanticDependencyFixtureMapper;

import org.springframework.stereotype.Service;

/** 已发布 资源血缘的 IService 持久化入口。 */
@Service
public class SemanticDependencyService
        extends ServiceImpl<SemanticDependencyFixtureMapper, SemanticDependencyEntity> {
    public void insertOrRestore(SemanticDependencyEntity row) {
        if (baseMapper.insertOrRestore(row) != 1) {
            throw new org.springframework.dao.DuplicateKeyException("候选目录包含重复记录");
        }
    }
}
