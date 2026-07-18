package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DimensionTimeGranularityEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DimensionTimeGranularityMapper
        extends AuditableBaseMapper<DimensionTimeGranularityEntity> {
    int insertGranularity(DimensionTimeGranularityEntity granularity);

    List<DimensionTimeGranularityEntity> selectByDimensionId(
            @Param("dimensionId") UUID dimensionId);

    int deleteByDimensionId(@Param("dimensionId") UUID dimensionId);
}
