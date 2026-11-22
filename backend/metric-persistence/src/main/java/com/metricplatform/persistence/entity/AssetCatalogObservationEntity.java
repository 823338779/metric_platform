package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("asset_catalog_observation")
public class AssetCatalogObservationEntity extends BaseEntity {
    private UUID environmentId;
    private UUID buildId;
    private Long scanSequence;
}
