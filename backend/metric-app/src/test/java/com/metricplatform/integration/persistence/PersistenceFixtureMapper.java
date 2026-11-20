package com.metricplatform.integration.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PersistenceFixtureMapper {
    @InterceptorIgnore(tenantLine = "true")
    int countTables(@Param("tableName") String tableName);

    @InterceptorIgnore(tenantLine = "true")
    String cleanupStateComment();
}
