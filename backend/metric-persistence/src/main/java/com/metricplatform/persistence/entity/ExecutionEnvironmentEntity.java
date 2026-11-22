package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@TableName("execution_environment")
public class ExecutionEnvironmentEntity extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private UUID environmentId;

    private String name;
    private long configVersion;

    public ExecutionEnvironmentEntity(UUID environmentId, String name, long configVersion) {
        this.environmentId = environmentId;
        this.name = name;
        this.configVersion = configVersion;
    }
}
