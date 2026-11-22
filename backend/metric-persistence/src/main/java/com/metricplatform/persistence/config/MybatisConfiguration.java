package com.metricplatform.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.typehandler.UuidTypeHandler;

import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration
public class MybatisConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditMetaObjectHandler auditMetaObjectHandler(Clock clock) {
        return new AuditMetaObjectHandler(clock);
    }

    @Bean
    UuidTypeHandler uuidHandler() {
        return new UuidTypeHandler();
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 优先约束租户，确保分页计数、行锁和更新都作用于同一租户。
        interceptor.addInnerInterceptor(
                new TenantLineInnerInterceptor(
                        new TenantLineHandler() {
                            @Override
                            public StringValue getTenantId() {
                                return new StringValue(TenantScope.require());
                            }

                            @Override
                            public boolean ignoreInsert(
                                    List<Column> columns, String tenantIdColumn) {
                                // 租户拦截器负责的写入只能使用当前作用域的租户。
                                if (columns.stream()
                                        .anyMatch(
                                                column ->
                                                        tenantIdColumn.equalsIgnoreCase(
                                                                column.getUnquotedColumnName()))) {
                                    throw new IllegalStateException("Mapper 不能显式指定租户列");
                                }
                                return false;
                            }
                        }));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
