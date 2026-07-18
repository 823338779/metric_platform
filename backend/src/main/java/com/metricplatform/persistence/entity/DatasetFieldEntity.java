package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dataset_field")
public class DatasetFieldEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID datasetId;
    private String physicalName, displayName, starrocksType;
    private String description, sensitivityLevel;
    private boolean dimensionEnabled, metricEnabled, selected;
    @Version private long version;
}
