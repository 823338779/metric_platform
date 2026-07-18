package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("audit_event")
public class AuditEventEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String actorId;
    private String action;
    private String resourceType;
    private UUID resourceId;
}
