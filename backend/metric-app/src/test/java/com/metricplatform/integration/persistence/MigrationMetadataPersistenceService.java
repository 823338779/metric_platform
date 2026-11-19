package com.metricplatform.integration.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Service;

/**
 * Test-only MyBatis-Plus boundary for verifying the PostgreSQL schema produced by Flyway.
 * Information-schema queries intentionally remain here instead of leaking SQL into migration tests.
 */
@Service
public class MigrationMetadataPersistenceService
        extends ServiceImpl<MigrationMetadataMapper, MigrationMetadataRow> {
    public int tableCount(String tableName) {
        return baseMapper.countTable(tableName);
    }

    public int columnCount(String tableName, String columnName) {
        return baseMapper.countColumn(tableName, columnName);
    }
}

// 仅测试使用的 information_schema 元数据不包含业务租户列。
@Mapper
@InterceptorIgnore(tenantLine = "true")
interface MigrationMetadataMapper extends BaseMapper<MigrationMetadataRow> {
    @Select(
            "select count(*) from information_schema.tables "
                    + "where table_schema='public' and table_name=#{tableName}")
    int countTable(@Param("tableName") String tableName);

    @Select(
            "select count(*) from information_schema.columns where table_schema='public' "
                    + "and table_name=#{tableName} and column_name=#{columnName}")
    int countColumn(@Param("tableName") String tableName, @Param("columnName") String columnName);
}

@TableName("information_schema.tables")
final class MigrationMetadataRow {}
