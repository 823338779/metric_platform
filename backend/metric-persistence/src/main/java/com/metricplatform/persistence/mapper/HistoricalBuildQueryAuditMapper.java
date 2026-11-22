package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.BuildQueryAuditEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HistoricalBuildQueryAuditMapper extends BaseMapper<BuildQueryAuditEntity> {}
