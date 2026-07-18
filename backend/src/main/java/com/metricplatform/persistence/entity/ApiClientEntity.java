package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("api_client")
public class ApiClientEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String clientName;
    private String tokenHash;
    private String roleCode;
}
