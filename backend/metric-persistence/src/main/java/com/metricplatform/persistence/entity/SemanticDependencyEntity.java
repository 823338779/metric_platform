package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** 同一发布版本内的原生血缘边。 */
@Getter
@Setter
@TableName("semantic_dependency")
public class SemanticDependencyEntity extends BaseEntity {
    /** 所属发布版本。 */
    private UUID releaseId;

    /** 上游资源标识。 */
    private String upstreamResourceId;

    /** 下游资源标识。 */
    private String downstreamResourceId;

    /** 血缘关系种类。 */
    @com.baomidou.mybatisplus.annotation.TableField("dependency_kind")
    private String kind;
}
