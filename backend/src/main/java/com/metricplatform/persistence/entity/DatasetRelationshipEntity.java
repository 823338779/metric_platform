package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dataset_relationship")
public class DatasetRelationshipEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String name;
    private UUID sourceDatasetId;
    private UUID targetDatasetId;
    private String cardinality;
    private String description, displayPath, semanticRole, status;
    private boolean fanoutSafe;
    private LocalDateTime lastValidatedAt;
    private String lastValidationStatus;
    @Version private long version;
}
