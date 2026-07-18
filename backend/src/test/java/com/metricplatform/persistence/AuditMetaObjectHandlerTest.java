package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.persistence.entity.DatasetEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

class AuditMetaObjectHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private final AuditMetaObjectHandler handler =
            new AuditMetaObjectHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fillsCreationAndInitialUpdateTimestampsOnInsert() {
        DatasetEntity entity = new DatasetEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getDeletedAt()).isNull();
    }

    @Test
    void normalUpdateDoesNotPopulateDeletedAt() {
        DatasetEntity entity = new DatasetEntity();

        handler.updateFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entity.getDeletedAt()).isNull();
    }

    @Test
    void logicalDeletePopulatesDeletedAt() {
        DatasetEntity entity = new DatasetEntity();
        entity.setDeleted(true);

        handler.updateFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entity.getDeletedAt()).isEqualTo(NOW);
    }
}
