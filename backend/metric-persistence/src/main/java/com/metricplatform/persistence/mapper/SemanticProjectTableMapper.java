package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.SemanticProjectEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SemanticProjectTableMapper extends BaseMapper<SemanticProjectEntity> {}
