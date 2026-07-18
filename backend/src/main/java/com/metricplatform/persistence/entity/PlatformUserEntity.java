package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_user")
public class PlatformUserEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private UUID tenantId;
    private String username;
    private String displayName;
    private String passwordHash;
    private String passwordAlgorithm;
    private String roleCode;
    private String status;
    private long authVersion;
    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant passwordChangedAt;
    private Instant lastLoginAt;
}
