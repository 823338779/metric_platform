package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.DataSourceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataSourceMapper extends BaseMapper<DataSourceEntity> {}
