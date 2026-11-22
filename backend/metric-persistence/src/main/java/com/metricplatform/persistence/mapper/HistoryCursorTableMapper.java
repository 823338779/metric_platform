package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.HistoryCursorEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HistoryCursorTableMapper extends BaseMapper<HistoryCursorEntity> {}
