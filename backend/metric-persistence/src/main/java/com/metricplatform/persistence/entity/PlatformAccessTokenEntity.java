package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_access_token")
public class PlatformAccessTokenEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String tokenHash;
    private UUID identityId;
    private long authVersion;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant revokedAt;
}
