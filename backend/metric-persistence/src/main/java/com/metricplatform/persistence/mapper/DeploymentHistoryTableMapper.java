package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.DeploymentHistoryEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeploymentHistoryTableMapper extends BaseMapper<DeploymentHistoryEntity> {}
