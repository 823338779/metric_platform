package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** 仓库发布检查点；该行锁保护业务快照与 SHA 的原子变化。 */
@Getter
@Setter
@TableName("git_sync_state")
public class GitSyncStateEntity {
    @TableId private String repositoryId;
    private String modelName;

    /** 首次消费固定的数据源身份，禁止复用现有逻辑 UUID 迁移到其他数据源。 */
    private UUID dataSourceId;

    /** 与数据源及模型共同锁定的 schema，避免相同 SHA 被错误当作已同步。 */
    private String schemaName;

    /** 最近成功 SHA，首次成功前为空。 */
    private String appliedCommit;

    /** 保留完整归一化定义，包括平台尚未直接暴露的同义词和主键。 */
    private String definitionJson;

    private String errorCode;
    private String errorMessage;
    private Instant updatedAt;
}
