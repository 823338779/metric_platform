package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.SemanticResourceEntity;

import org.apache.ibatis.annotations.Mapper;

/** 目录资源的 MyBatis Plus 基础映射。 */
@Mapper
public interface SemanticResourceMapper extends BaseMapper<SemanticResourceEntity> {}
