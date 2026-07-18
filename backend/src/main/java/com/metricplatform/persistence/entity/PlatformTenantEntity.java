package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_tenant")
public class PlatformTenantEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String tenantKey;
    private String displayName;
    private String status;
}
