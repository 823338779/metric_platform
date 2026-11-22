package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("asset_user_grant")
public class AssetUserGrantEntity extends BaseEntity {
    private UUID userId;
    private UUID assetId;
}
