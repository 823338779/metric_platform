package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DimensionTimeGranularityEntity;
import com.metricplatform.persistence.mapper.DimensionTimeGranularityMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DimensionTimeGranularityPersistenceService
        extends ServiceImpl<DimensionTimeGranularityMapper, DimensionTimeGranularityEntity> {
    public void saveGranularity(DimensionTimeGranularityEntity granularity) {
        baseMapper.insertGranularity(granularity);
    }

    public List<DimensionTimeGranularityEntity> listByDimensionId(UUID dimensionId) {
        return baseMapper.selectByDimensionId(dimensionId);
    }

    public void removeByDimensionId(UUID dimensionId) {
        baseMapper.deleteByDimensionId(dimensionId);
    }
}
