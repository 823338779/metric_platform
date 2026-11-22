package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.EnvironmentBuildSubmissionEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface EnvironmentBuildSubmissionMapper
        extends BaseMapper<EnvironmentBuildSubmissionEntity> {
    @InterceptorIgnore(tenantLine = "true")
    void lockIdempotencyKey(@Param("scope") String scope);

    // 两个 INSERT ... SELECT 均从已按租户限定的提交继承归属。
    @InterceptorIgnore(tenantLine = "true")
    int bindOwnership(
            @Param("tenant") String tenant, @Param("actor") UUID actor, @Param("key") String key);

    @InterceptorIgnore(tenantLine = "true")
    int bindTarget(
            @Param("tenant") String tenant, @Param("actor") UUID actor, @Param("key") String key);
}
