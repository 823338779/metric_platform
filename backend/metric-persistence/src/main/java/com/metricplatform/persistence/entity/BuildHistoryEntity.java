package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** 服务事实的只读历史投影。 */
@Getter
@Setter
@TableName(value = "build_history", autoResultMap = true)
public class BuildHistoryEntity extends BaseEntity {
    /** 构建唯一身份。 */
    @TableId(type = IdType.INPUT)
    private UUID buildId;

    /** 规范仓库地址。 */
    private String repository;

    /** 来源分支。 */
    private String branchName;

    /** 服务对象版本。 */
    private long objectVersion;

    /** 首次入库的变化序号；后续对象更新不得改变历史分页位置。 */
    private long firstSequence;

    /** 最后应用序号。 */
    private long lastSequence;

    /** 只读事实摘要。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String summary;

    /** 摘要协议版本。 */
    private int schemaVersion;

    /** 平台同步时间。 */
    private java.time.OffsetDateTime syncedAt;
}
