package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import java.util.UUID;

@TableName("dataset_relationship")
public class DatasetRelationshipEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String name;
    private UUID sourceDatasetId;
    private UUID targetDatasetId;
    private String cardinality;
    private LocalDateTime lastValidatedAt;
    private String lastValidationStatus;
    @Version private long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        id = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public UUID getSourceDatasetId() {
        return sourceDatasetId;
    }

    public void setSourceDatasetId(UUID v) {
        sourceDatasetId = v;
    }

    public UUID getTargetDatasetId() {
        return targetDatasetId;
    }

    public void setTargetDatasetId(UUID v) {
        targetDatasetId = v;
    }

    public String getCardinality() {
        return cardinality;
    }

    public void setCardinality(String v) {
        cardinality = v;
    }

    public LocalDateTime getLastValidatedAt() {
        return lastValidatedAt;
    }

    public void setLastValidatedAt(LocalDateTime v) {
        lastValidatedAt = v;
    }

    public String getLastValidationStatus() {
        return lastValidationStatus;
    }

    public void setLastValidationStatus(String v) {
        lastValidationStatus = v;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long v) {
        version = v;
    }
}
