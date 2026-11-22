package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticResourceEntity;
import com.metricplatform.persistence.mapper.SemanticResourceFixtureMapper;

import org.springframework.stereotype.Service;

/** 已发布 语义资源的 IService 持久化入口。 */
@Service
public class SemanticResourceService
        extends ServiceImpl<SemanticResourceFixtureMapper, SemanticResourceEntity> {
    public void insertOrRestore(SemanticResourceEntity row) {
        if (baseMapper.insertOrRestore(row) != 1) {
            throw new org.springframework.dao.DuplicateKeyException("候选目录包含重复记录");
        }
    }
}
