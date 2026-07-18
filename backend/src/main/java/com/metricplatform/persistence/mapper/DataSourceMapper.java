package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DataSourceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataSourceMapper extends AuditableBaseMapper<DataSourceEntity> {
    List<DataSourceEntity> selectAllIncludingDeleted();
}
