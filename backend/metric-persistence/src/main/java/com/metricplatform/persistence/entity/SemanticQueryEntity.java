package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 固定发布版本的异步查询任务。 */
@Getter
@Setter
@TableName("semantic_query")
public class SemanticQueryEntity {
    /** 平台查询任务标识。 */
    @TableId(type = IdType.INPUT)
    private UUID queryId;

    /** 查询所属项目。 */
    private String projectId;

    /** 查询固定的发布版本。 */
    private UUID releaseId;

    /** 经可信迁移报告关联的新构建身份；未知来源保持为空。 */
    private UUID buildId;

    /** 发起新查询的平台身份。 */
    private UUID userId;

    /** 运行服务受理幂等键。 */
    private UUID idempotencyKey;

    /** 运行服务查询任务标识。 */
    private UUID runtimeQueryId;

    /** 查询、解释、预览或维度值模式。 */
    private String mode;

    /** 查询生命周期状态。 */
    private String status;

    /** 无凭据结果定位符。 */
    private String resultLocator;

    /** 脱敏错误码。 */
    private String errorCode;

    /** 任务接收时间。 */
    private OffsetDateTime submittedAt;

    /** 任务终态时间。 */
    private OffsetDateTime completedAt;
}
