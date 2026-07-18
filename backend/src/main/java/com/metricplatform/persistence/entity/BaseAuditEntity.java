package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class BaseAuditEntity {
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    @TableLogic(value = "false", delval = "true")
    private boolean deleted;

    @TableField(value = "deleted_at", fill = FieldFill.UPDATE)
    private Instant deletedAt;
}
