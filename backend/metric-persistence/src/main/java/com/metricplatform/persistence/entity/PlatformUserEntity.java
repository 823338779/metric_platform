package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@TableName("platform_user")
public class PlatformUserEntity extends BaseAuditEntity {
    @TableId private UUID id;

    /** 账号唯一所属租户。 */
    private String tenantId;

    private String username;
    private String displayName;
    private String passwordHash;
    private String passwordAlgorithm;
    private String status;
    private long authVersion;
    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant passwordChangedAt;
    private Instant lastLoginAt;
}
