package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

/** 服务事实的只读历史投影。 */
@Getter
@Setter
@TableName(value = "deployment_history", autoResultMap = true)
public class DeploymentHistoryEntity extends BaseEntity {
    /** 对象类型。 */
    private String objectType;

    /** 服务对象稳定身份。 */
    private String objectId;

    /** 规范仓库地址。 */
    private String repository;

    /** 服务对象版本。 */
    private long objectVersion;

    /** 最后应用序号。 */
    private long lastSequence;

    /** 只读部署摘要。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String summary;

    /** 平台同步时间。 */
    private java.time.OffsetDateTime syncedAt;
}
