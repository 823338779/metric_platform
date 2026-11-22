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
@TableName("build_query_audit")
public class BuildQueryAuditEntity extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private UUID queryId;

    private UUID buildId;
    private UUID userId;
    private String idempotencyKey;
    private Instant submittedAt;
}
