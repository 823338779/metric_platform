package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** 永久归属记录不跟随业务对象软删除；同一逻辑名恢复时复用原 UUID。 */
@Getter
@Setter
@TableName("git_object_ownership")
public class GitObjectOwnershipEntity {
    @TableId private UUID objectId;
    private String repositoryId;
    private String modelName;

    /** 固定白名单内的平台表类型。 */
    private String kind;

    /** 字段与维度以 dataset.field 表示，其余为模型内名称。 */
    private String logicalName;
}
