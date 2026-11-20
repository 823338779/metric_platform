package com.metricplatform.environment;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentFixtureMapper {
    void insertEnvironment(@Param("environmentId") UUID environmentId);

    void insertSubmission(
            @Param("environmentId") UUID environmentId,
            @Param("actor") UUID actor,
            @Param("key") String key);

    List<UUID> submissionBuildIds(@Param("actor") UUID actor, @Param("key") String key);

    // 全局观察和归属断言必须能够发现误写到其他租户的记录。
    @InterceptorIgnore(tenantLine = "true")
    List<UUID> observedBuildIds(@Param("environmentId") UUID environmentId);

    @InterceptorIgnore(tenantLine = "true")
    List<String> recordedTenants(
            @Param("environmentId") UUID environmentId, @Param("buildId") UUID buildId);
}
