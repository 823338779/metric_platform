package com.metricplatform.identity;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 夹具在业务作用域建立前开通租户，并用于核验全局租户数量和编号序列。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface TenantFixtureMapper {
    void ensureManager(@Param("tenantId") String tenantId, @Param("managerId") UUID managerId);

    void bindBuild(
            @Param("tenantId") String tenantId,
            @Param("buildId") UUID buildId,
            @Param("environmentId") UUID environmentId);

    int tenantCount();

    long tenantSequenceValue();

    boolean tenantSequenceCalled();

    long setTenantSequence(@Param("value") long value, @Param("called") boolean called);

    String databaseVersion();

    List<String> businessTables();

    List<String> tablesMissingAuditFields();
}
