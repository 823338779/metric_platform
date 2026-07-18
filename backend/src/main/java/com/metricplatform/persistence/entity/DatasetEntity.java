package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.util.UUID;

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
    @Version private long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        id = v;
    }

    public UUID getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(UUID v) {
        dataSourceId = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String v) {
        schemaName = v;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String v) {
        objectName = v;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String v) {
        objectType = v;
    }

    public String getFilterExpression() {
        return filterExpression;
    }

    public void setFilterExpression(String v) {
        filterExpression = v;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long v) {
        version = v;
    }
}
