package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;

@TableName("dimension_time_granularity")
public class DimensionTimeGranularityEntity {
    private UUID dimensionId;
    private String granularity;

    public UUID getDimensionId() {
        return dimensionId;
    }

    public void setDimensionId(UUID v) {
        dimensionId = v;
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String v) {
        granularity = v;
    }
}
