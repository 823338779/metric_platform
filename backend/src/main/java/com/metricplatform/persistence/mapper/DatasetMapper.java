package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.DatasetEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasetMapper extends BaseMapper<DatasetEntity> {}
