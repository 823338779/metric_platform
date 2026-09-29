package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.mapper.SemanticProjectMapper;
import org.springframework.stereotype.Service;

/** 项目常规读写使用 IService，并集中封装绑定与发布所需的行锁。 */
@Service
public class SemanticProjectService
        extends ServiceImpl<SemanticProjectMapper, SemanticProjectEntity> {
    public void insertBinding(SemanticProjectEntity project) {
        baseMapper.insertBinding(project);
    }

    public SemanticProjectEntity lockProject(String projectId) {
        return baseMapper.lockProject(projectId);
    }
}
