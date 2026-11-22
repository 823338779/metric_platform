package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("asset_build_member")
public class AssetBuildMemberEntity extends BaseEntity {
    private UUID buildId;
    private UUID assetId;
    private String queryName;
    private String selector;
    private String nodeId;
}
