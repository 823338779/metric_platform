package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.BuildOwnershipEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface AssetOwnershipMapper extends BaseMapper<BuildOwnershipEntity> {
    boolean hasEnvironment(@Param("tenant") String tenant, @Param("environment") UUID environment);

    UUID cursorSecret();
}
