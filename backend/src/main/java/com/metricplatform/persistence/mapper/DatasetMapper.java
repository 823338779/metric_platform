package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DatasetEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasetMapper extends AuditableBaseMapper<DatasetEntity> {}
