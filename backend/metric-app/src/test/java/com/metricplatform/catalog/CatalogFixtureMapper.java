package com.metricplatform.catalog;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CatalogFixtureMapper {
    UUID managerId();

    void insertReader(@Param("id") UUID id, @Param("displayName") String displayName);

    // 跨租户检查此次构建只产生预期用户的审计记录。
    @InterceptorIgnore(tenantLine = "true")
    UUID auditedUser(@Param("buildId") UUID buildId);
}
