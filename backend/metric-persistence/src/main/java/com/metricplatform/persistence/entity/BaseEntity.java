package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public abstract class BaseEntity {
    /** 记录归属由 SQL 租户拦截器写入，实体更新不能转移归属。 */
    @TableField(
            value = "tenant_id",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private String tenantId;

    @TableField(value = "created_at", fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    @TableLogic(value = "false", delval = "true")
    private boolean deleted;

    @TableField(value = "deleted_at", fill = FieldFill.UPDATE)
    private Instant deletedAt;
}
