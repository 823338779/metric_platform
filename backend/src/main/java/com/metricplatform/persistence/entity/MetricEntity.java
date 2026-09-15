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

    /** 指标级固定过滤的 FilterExpression JSON；空值表示无过滤，仅作用于本指标聚合。 */
    private String fixedFilterExpression;

    private UUID defaultTimeDimensionId;
    @Version private long version;
}
