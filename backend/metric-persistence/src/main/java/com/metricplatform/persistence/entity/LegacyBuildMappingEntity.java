package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@TableName("legacy_build_mapping")
public class LegacyBuildMappingEntity extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private UUID releaseId;

    private UUID buildId;
    private String repository;
    private String sourceEvidence;
}
