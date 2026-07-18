package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dimension")
public class DimensionEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID datasetId, datasetFieldId;
    private String name, displayName, dimensionType;
    private String description, businessDefinition, domainName, ownerName;
    private String status, certificationStatus, semanticType, valueType;
    private String defaultGranularity, sensitivityLevel, aliases;
    @Version private long version;
}
