package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@TableName("query_execution_audit")
public class QueryExecutionAuditEntity extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private UUID executionId;

    private UUID buildId;
    private UUID userId;
    private Instant completedAt;
}
