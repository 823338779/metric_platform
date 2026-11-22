package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@TableName(value = "tenant_cutover_submission_archive", autoResultMap = true)
public class TenantCutoverSubmissionArchiveEntity extends BaseEntity {
    private UUID actorId;
    private String idempotencyKey;
    private UUID environmentId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String command;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String request;

    private UUID buildId;
    private String archiveStatus;
    private Instant archivedAt;
}
