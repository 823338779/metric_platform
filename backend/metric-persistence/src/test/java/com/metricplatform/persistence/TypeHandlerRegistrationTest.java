package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.persistence.typehandler.JsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.StringTypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.Test;

class TypeHandlerRegistrationTest {
    private static final String HANDLER_PACKAGE = "com.metricplatform.persistence";

    @Test
    void packageScanningKeepsOrdinaryStringsSeparateFromJsonb() {
        // 与实际应用相同的包扫描不能将普通字符串查询参数注册为 JSONB。
        TypeHandlerRegistry registry = new TypeHandlerRegistry();
        registry.register(HANDLER_PACKAGE);
        assertThat(registry.getTypeHandler(String.class)).isInstanceOf(StringTypeHandler.class);
        assertThat(registry.getTypeHandler(String.class, JdbcType.VARCHAR))
                .isInstanceOf(StringTypeHandler.class);
        assertThat(registry.getTypeHandler(String.class, JdbcType.OTHER))
                .isInstanceOf(JsonbTypeHandler.class);
    }
}
