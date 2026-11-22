package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName(value = "execution_environment_version", autoResultMap = true)
public class ExecutionEnvironmentVersionEntity extends BaseEntity {
    private UUID environmentId;
    private Long configVersion;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String execution;
}
