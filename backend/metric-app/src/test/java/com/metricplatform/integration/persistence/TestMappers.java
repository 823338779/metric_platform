package com.metricplatform.integration.persistence;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.metricplatform.persistence.typehandler.UuidTypeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;

final class TestMappers {
    private TestMappers() {}

    static HikariDataSource database(String url) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("test");
        config.setPassword("test");
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    static <T> T get(DataSource source, Class<T> type) throws Exception {
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeHandlers(new UuidTypeHandler());
        factory.setMapperLocations(
                new ClassPathResource("mapper/" + type.getSimpleName() + ".xml"));
        return new SqlSessionTemplate(factory.getObject()).getMapper(type);
    }
}
