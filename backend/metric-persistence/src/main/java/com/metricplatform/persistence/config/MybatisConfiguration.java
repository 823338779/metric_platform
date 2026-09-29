package com.metricplatform.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.metricplatform.persistence.typehandler.UuidTypeHandler;
import java.time.Clock;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    static BeanPostProcessor uuidHandler() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name)
                    throws BeansException {
                if (bean instanceof SqlSessionFactory factory)
                    factory.getConfiguration()
                            .getTypeHandlerRegistry()
                            .register(UUID.class, UuidTypeHandler.class);
                return bean;
            }
        };
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
