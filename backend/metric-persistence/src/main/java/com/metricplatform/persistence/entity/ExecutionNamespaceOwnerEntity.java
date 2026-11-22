package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("execution_namespace_owner")
public class ExecutionNamespaceOwnerEntity extends BaseEntity {
    private String instanceId;
    private String adapterType;
    private String catalogName;
    private String schemaName;
    private UUID environmentId;
}
