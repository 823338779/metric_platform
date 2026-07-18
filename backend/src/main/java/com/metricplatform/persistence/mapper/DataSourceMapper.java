package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DataSourceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DataSourceMapper extends AuditableBaseMapper<DataSourceEntity> {
    @Select("SELECT * FROM data_source ORDER BY name")
    List<DataSourceEntity> selectAllIncludingDeleted();
}
