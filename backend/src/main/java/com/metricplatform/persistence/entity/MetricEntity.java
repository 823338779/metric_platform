package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;

@TableName("metric")
public class MetricEntity {
    @TableId private UUID id;
    private UUID datasetId, datasetFieldId;
    private String name, displayName, aggregation;
    @Version private long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        id = v;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(UUID v) {
        datasetId = v;
    }

    public UUID getDatasetFieldId() {
        return datasetFieldId;
    }

    public void setDatasetFieldId(UUID v) {
        datasetFieldId = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String v) {
        displayName = v;
    }

    public String getAggregation() {
        return aggregation;
    }

    public void setAggregation(String v) {
        aggregation = v;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long v) {
        version = v;
    }
}
