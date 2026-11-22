package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("platform_tenant")
public class TenantEntity extends BaseEntity {
    private UUID managerId;
    private UUID cursorSecret;
}
