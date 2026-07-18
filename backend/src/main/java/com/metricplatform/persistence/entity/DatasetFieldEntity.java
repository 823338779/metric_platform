package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;

@TableName("dataset_field")
public class DatasetFieldEntity {
    @TableId private UUID id;
    private UUID datasetId;
    private String physicalName, displayName, starrocksType;
    private boolean dimensionEnabled, metricEnabled, selected;
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

    public String getPhysicalName() {
        return physicalName;
    }

    public void setPhysicalName(String v) {
        physicalName = v;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String v) {
        displayName = v;
    }

    public String getStarrocksType() {
        return starrocksType;
    }

    public void setStarrocksType(String v) {
        starrocksType = v;
    }

    public boolean isDimensionEnabled() {
        return dimensionEnabled;
    }

    public void setDimensionEnabled(boolean v) {
        dimensionEnabled = v;
    }

    public boolean isMetricEnabled() {
        return metricEnabled;
    }

    public void setMetricEnabled(boolean v) {
        metricEnabled = v;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean v) {
        selected = v;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long v) {
        version = v;
    }
}
