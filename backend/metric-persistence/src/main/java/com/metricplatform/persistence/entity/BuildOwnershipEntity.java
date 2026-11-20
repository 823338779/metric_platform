package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;

import java.util.UUID;

@Getter
@TableName("build_ownership")
public class BuildOwnershipEntity extends TenantOwnedEntity {
    @TableId(type = IdType.INPUT)
    private UUID buildId;

    private UUID environmentId;
}
