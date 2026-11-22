package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.LegacyBuildMappingEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LegacyBuildMappingMapper extends BaseMapper<LegacyBuildMappingEntity> {}
