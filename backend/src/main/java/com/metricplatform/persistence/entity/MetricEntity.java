package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("metric")
public class MetricEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID datasetId, datasetFieldId;
    private String name, displayName, aggregation;
    private String description, businessDefinition, domainName, ownerName;
    private String status, certificationStatus, unit, formatPattern;
    private String aggregationBehavior, additivity, metricType, aliases;
    private UUID defaultTimeDimensionId;
    @Version private long version;
}
