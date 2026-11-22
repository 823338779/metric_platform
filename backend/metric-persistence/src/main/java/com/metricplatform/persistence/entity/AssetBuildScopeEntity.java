package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("asset_build_scope")
public class AssetBuildScopeEntity extends BaseEntity {
    private UUID buildId;
    private UUID environmentId;
    private String repository;
    private String branchName;
    private Boolean indexed;
}
