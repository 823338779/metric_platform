package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.MetricEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricMapper extends BaseMapper<MetricEntity> {}
