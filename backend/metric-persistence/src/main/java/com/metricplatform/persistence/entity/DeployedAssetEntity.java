package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("deployed_asset")
public class DeployedAssetEntity extends BaseEntity {
    private UUID assetId;
    private UUID ownerId;
    private UUID environmentId;
    private String kind;
    private String dimensionName;
    private String canonicalName;
}
