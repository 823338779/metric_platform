package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

import lombok.Getter;

/** 映射带租户归属但没有通用审计列的业务表。 */
@Getter
public abstract class TenantOwnedEntity {
    /** 从数据库读取归属，写入时由租户拦截器提供。 */
    @TableField(
            value = "tenant_id",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private String tenantId;
}
