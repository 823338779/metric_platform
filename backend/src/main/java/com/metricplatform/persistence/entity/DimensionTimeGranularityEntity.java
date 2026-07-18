package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dimension_time_granularity")
public class DimensionTimeGranularityEntity extends BaseAuditEntity {
    private UUID dimensionId;
    private String granularity;
}
