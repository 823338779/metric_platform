package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor
@TableName("execution_environment")
public class ExecutionEnvironmentEntity extends TenantOwnedEntity {
    @TableId(type = IdType.INPUT)
    private UUID environmentId;

    private String name;
    private long configVersion;

    @TableField(
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.ALWAYS,
            update = "CURRENT_TIMESTAMP")
    private Instant updatedAt;

    public ExecutionEnvironmentEntity(UUID environmentId, String name, long configVersion) {
        this.environmentId = environmentId;
        this.name = name;
        this.configVersion = configVersion;
    }
}
