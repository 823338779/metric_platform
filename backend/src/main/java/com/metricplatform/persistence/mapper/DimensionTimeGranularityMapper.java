package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DimensionTimeGranularityEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DimensionTimeGranularityMapper
        extends AuditableBaseMapper<DimensionTimeGranularityEntity> {
    @Insert(
            "INSERT INTO dimension_time_granularity (dimension_id, granularity) VALUES (#{dimensionId}, #{granularity})")
    int insertGranularity(DimensionTimeGranularityEntity granularity);

    @Select(
            "SELECT dimension_id, granularity FROM dimension_time_granularity WHERE dimension_id = #{dimensionId} ORDER BY granularity")
    List<DimensionTimeGranularityEntity> selectByDimensionId(UUID dimensionId);

    @Delete("DELETE FROM dimension_time_granularity WHERE dimension_id = #{dimensionId}")
    int deleteByDimensionId(UUID dimensionId);
}
