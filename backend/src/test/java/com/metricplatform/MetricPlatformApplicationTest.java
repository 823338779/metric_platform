package com.metricplatform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.mapper.AuditEventMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MetricPlatformApplicationTest {
    @Autowired private AuditEventMapper auditEventMapper;

    @Autowired private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Value("${metric-platform.test-profile:false}")
    private boolean namedTestProfileLoaded;

    @Test
    void contextLoads() {}

    @Test
    void mapsEveryMetadataTableToAnEntityAndBaseMapper() throws ClassNotFoundException {
        Map<String, String> entityTables =
                Map.of(
                        "PlatformUserEntity", "platform_user",
                        "ApiClientEntity", "api_client",
                        "DataSourceEntity", "data_source",
                        "DatasetEntity", "dataset",
                        "DatasetFieldEntity", "dataset_field",
                        "DimensionEntity", "dimension",
                        "MetricEntity", "metric",
                        "AuditEventEntity", "audit_event");

        for (Map.Entry<String, String> entityTable : entityTables.entrySet()) {
            Class<?> entity =
                    Class.forName("com.metricplatform.persistence.entity." + entityTable.getKey());
            TableName tableName = entity.getAnnotation(TableName.class);
            assertEquals(entityTable.getValue(), tableName.value());

            Class<?> mapper =
                    Class.forName(
                            "com.metricplatform.persistence.mapper."
                                    + entityTable.getKey().replace("Entity", "Mapper"));
            assertTrue(BaseMapper.class.isAssignableFrom(mapper));
        }
    }

    @Test
    void testProfileUsesTheH2Datasource() throws SQLException {
        assertTrue(namedTestProfileLoaded);
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:"));
        }
    }

    @Test
    void auditEventsCannotBeUpdatedOrDeleted() {
        AuditEventEntity event = new AuditEventEntity();
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
