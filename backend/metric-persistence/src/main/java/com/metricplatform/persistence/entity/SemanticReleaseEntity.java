package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** 发布候选的持久记录；业务规则由 core 和发布用例决定。 */
@Getter
@Setter
@TableName(value = "semantic_release", autoResultMap = true)
public class SemanticReleaseEntity {
    /** 平台发布版本 ID。 */
    @TableId(type = IdType.INPUT)
    private UUID releaseId;

    /** 固定项目 ID。 */
    private String projectId;

    /** 项目内单调候选序号。 */
    private Long releaseSequence;

    /** 创建候选时的生效指针。 */
    private UUID expectedActiveReleaseId;

    /** 目标 main SHA。 */
    private String targetCommitSha;

    /** 实际构建源 SHA。 */
    private String buildSourceSha;

    /** 受支持 项目输入摘要。 */
    private String projectDigest;

    /** 受控构建配置版本。 */
    private String configVersion;

    /** 期望工具链版本。 */
    private String expectedToolchainVersion;

    /** 服务实际工具链版本。 */
    private String toolchainVersion;

    /** 构建提交幂等键。 */
    private UUID idempotencyKey;

    /** 提交操作是否曾尝试。 */
    private Boolean submissionAttempted;

    /** 自动重试次数。 */
    private Integer transientRetryCount;

    /** 下次重试时间。 */
    private OffsetDateTime retryAfter;

    /** 运行服务任务 ID。 */
    private UUID runtimeRunId;

    /** 该 run 独占的 schema。 */
    private String schemaName;

    /** 发布状态。 */
    private String state;

    /** 物理清理状态。 */
    private String cleanupState;

    /** 产物校验和版本信息。 */
    @TableField(value = "validation_json", typeHandler = JsonbTypeHandler.class)
    private String validationJson;

    /** 脱敏失败码。 */
    private String errorCode;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    /** 生效时间。 */
    private OffsetDateTime publishedAt;
}
