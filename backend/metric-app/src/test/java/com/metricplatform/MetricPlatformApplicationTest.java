package com.metricplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.BaseEntity;
import com.metricplatform.persistence.entity.EventEntity;
import com.metricplatform.persistence.mapper.AuditEventMapper;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ResolvableType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MetricPlatformApplicationTest {
    @Autowired private AuditEventMapper auditEventMapper;

    @Autowired private TenantFixtureMapper database;

    @org.springframework.beans.factory.annotation.Value("${metric-platform.test-profile:false}")
    private boolean namedTestProfileLoaded;

    @Test
    void contextLoads() {}

    @Autowired private SqlSessionTemplate sqlSessions;

    @Test
    void mapsEveryMetadataTableToAnEntityAndBaseMapper() {
        var businessTables = database.businessTables();
        var mappedTables = new HashSet<String>();
        for (var mapper : sqlSessions.getConfiguration().getMapperRegistry().getMappers()) {
            var entity = ResolvableType.forClass(mapper).as(BaseMapper.class).resolveGeneric(0);
            if (entity == null
                    || !entity.getPackageName().equals(BaseEntity.class.getPackageName())) {
                continue;
            }
            assertThat(entity).isAssignableTo(BaseEntity.class);
            assertThat(entity.getDeclaredAnnotation(TableName.class))
                    .as(entity.getName())
                    .isNotNull();
            String table = entity.getDeclaredAnnotation(TableName.class).value();
            if (mappedTables.add(table) && businessTables.contains(table)) {
                try (var scope = TenantScope.open(TenantFixture.ID)) {
                    BaseMapper<?> rows = (BaseMapper<?>) sqlSessions.getMapper(mapper);
                    verifyMappedColumns(rows);
                }
            }
        }
        assertThat(mappedTables).containsAll(businessTables);
        assertThat(database.tablesMissingAuditFields()).isEmpty();
    }

    private static <T> void verifyMappedColumns(BaseMapper<T> rows) {
        assertThat(rows.selectList(new QueryWrapper<T>().last("LIMIT 0"))).isEmpty();
    }

    @Test
    void testProfileUsesThePostgreSqlDatasource() {
        assertTrue(namedTestProfileLoaded);
        assertTrue(database.databaseVersion().startsWith("PostgreSQL "));
    }

    @Test
    void auditEventsCannotBeUpdatedOrDeleted() {
        EventEntity event = new EventEntity();
        event.setId(UUID.randomUUID());

        assertThrows(UnsupportedOperationException.class, () -> auditEventMapper.updateById(event));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.deleteById(event.getId()));
        assertThrows(
                UnsupportedOperationException.class, () -> auditEventMapper.insertOrUpdate(event));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.updateById(List.of(event)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.updateById(List.of(event), 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.insertOrUpdate(List.of(event)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.insertOrUpdate(List.of(event), 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.insertOrUpdate(List.of(event), (session, item) -> true));
        assertThrows(
                UnsupportedOperationException.class,
                () -> auditEventMapper.insertOrUpdate(List.of(event), (session, item) -> true, 1));
    }
}
