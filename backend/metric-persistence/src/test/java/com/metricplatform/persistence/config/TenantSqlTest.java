package com.metricplatform.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.mapper.BuildHistoryMapper;
import com.metricplatform.persistence.mapper.DeploymentHistoryMapper;
import com.metricplatform.persistence.mapper.HistoryCursorMapper;
import com.metricplatform.persistence.mapper.SemanticProjectMapper;
import com.metricplatform.persistence.mapper.SemanticQueryMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class TenantSqlTest {
    private static final String TENANT = "tn_12345";
    private static final String OTHER = "tn_54321";
    private final TenantLineInnerInterceptor interceptor =
            (TenantLineInnerInterceptor)
                    new MybatisConfiguration()
                            .mybatisPlusInterceptor()
                            .getInterceptors()
                            .getFirst();

    @Test
    void requiresTrustedScopeAndClearsItAfterFailure() {
        assertThatThrownBy(() -> sql(HistoryCursorMapper.READ))
                .isInstanceOf(IllegalStateException.class);
        try (var outer = TenantScope.open(TENANT)) {
            try (var inner = TenantScope.open(TENANT)) {
                assertThatThrownBy(() -> TenantScope.open(OTHER))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(TenantScope.require()).isEqualTo(TENANT);
        }
        assertThat(TenantScope.current()).isNull();
    }

    @Test
    void scopesCustomReadsLocksAndCompareAndSetUpdates() {
        try (var scope = TenantScope.open(TENANT)) {
            for (String statement :
                    List.of(
                            BuildHistoryMapper.LIST,
                            HistoryCursorMapper.LOCK,
                            HistoryCursorMapper.ADVANCE,
                            SemanticQueryMapper.LOCK_RELEASE_SQL)) {
                assertThat(sql(statement)).contains("tenant_id = '" + TENANT + "'");
            }
        }
    }

    @Test
    void upsertsUseTenantForBothInsertAndConflictArbitration() {
        try (var scope = TenantScope.open(TENANT)) {
            assertThatThrownBy(
                            () ->
                                    sql(
                                            "insert into semantic_project (\"tenant_id\","
                                                + " project_id) values (?, ?)"))
                    .isInstanceOf(IllegalStateException.class);
            for (String statement :
                    List.of(
                            BuildHistoryMapper.UPSERT,
                            DeploymentHistoryMapper.UPSERT,
                            SemanticProjectMapper.INSERT_BINDING_SQL,
                            HistoryCursorMapper.INITIALIZE)) {
                assertThat(sql(statement))
                        .contains("tenant_id)", "'" + TENANT + "'")
                        .containsPattern("ON CONFLICT \\(\\s*tenant_id,");
            }
        }
    }

    private String sql(String statement) {
        return interceptor.parserSingle(statement.replaceAll("#\\{[^}]+}", "?"), null);
    }
}
