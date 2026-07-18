package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_user")
public class PlatformUserEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String username;
    private String roleCode;
}
