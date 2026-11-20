package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface EnvironmentBuildSubmissionMapper {
    record SubmissionRow(UUID environmentId, String command, String request) {}

    @InterceptorIgnore(tenantLine = "true")
    void lockIdempotencyKey(@Param("scope") String scope);

    SubmissionRow find(@Param("actor") UUID actor, @Param("key") String key);

    void insert(
            @Param("actor") UUID actor,
            @Param("key") String key,
            @Param("environment") UUID environment,
            @Param("command") String command,
            @Param("request") String request);

    int accept(
            @Param("actor") UUID actor, @Param("key") String key, @Param("buildId") UUID buildId);

    // 两个 INSERT ... SELECT 均从已按租户限定的提交继承归属。
    @InterceptorIgnore(tenantLine = "true")
    int bindOwnership(
            @Param("tenant") String tenant, @Param("actor") UUID actor, @Param("key") String key);

    @InterceptorIgnore(tenantLine = "true")
    int bindTarget(
            @Param("tenant") String tenant, @Param("actor") UUID actor, @Param("key") String key);
}
