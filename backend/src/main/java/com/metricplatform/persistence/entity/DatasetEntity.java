package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dataset")
public class DatasetEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID dataSourceId;
    private String name;
    private String description;
    private String schemaName;
    private String objectName;
    private String objectType;
    private String filterExpression;
    private String businessGrain, freshnessPolicy, qualityStatus;
    private String domainName, ownerName, status, certificationStatus, aliases;
    private UUID defaultTimeDimensionId;
    @Version private long version;
}
