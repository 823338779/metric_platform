package com.metricplatform.dbt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** 受控 dbt 项目绑定与当前发布指针。 */
@Getter
@Setter
@TableName("semantic_project")
public class SemanticProjectEntity {
    /** 固定项目标识。 */
    @TableId(type = IdType.INPUT)
    private String projectId;
    /** 固定仓库绑定标识。 */
    private String repositoryId;
    /** 仓库内固定子目录。 */
    private String projectSubdir;
    /** 运行服务 profile target 绑定。 */
    private String profileBindingId;
    /** 最近观察的 main SHA。 */
    private String observedSha;
    /** 最近观察的 dbt 输入摘要。 */
    private String observedProjectDigest;
    /** 最近观察的构建配置版本。 */
    private String observedConfigVersion;
    /** 最近观察状态。 */
    private String lastObservationStatus;
    /** 最近观察时间。 */
    private OffsetDateTime lastObservedAt;
    /** 当前已发布版本。 */
    private UUID activeReleaseId;
    /** 发布候选单调序号。 */
    private Long publicationSequence;
}
