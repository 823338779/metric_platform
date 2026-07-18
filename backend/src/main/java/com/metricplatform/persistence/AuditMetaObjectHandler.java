package com.metricplatform.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.metricplatform.persistence.entity.BaseAuditEntity;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;

@RequiredArgsConstructor
public class AuditMetaObjectHandler implements MetaObjectHandler {
    private final Clock clock;

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = clock.instant();
        fillWhenNull(metaObject, "createdAt", now);
        fillWhenNull(metaObject, "updatedAt", now);
        fillWhenNull(metaObject, "deleted", false);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Instant now = clock.instant();
        setFieldValByName("updatedAt", now, metaObject);
        BaseAuditEntity entity = auditedEntity(metaObject);
        if (entity != null && entity.isDeleted()) {
            setFieldValByName("deletedAt", now, metaObject);
        }
    }

    private static BaseAuditEntity auditedEntity(MetaObject metaObject) {
        if (metaObject.getOriginalObject() instanceof BaseAuditEntity entity) {
            return entity;
        }
        if (metaObject.hasGetter("et")
                && metaObject.getValue("et") instanceof BaseAuditEntity entity) {
            return entity;
        }
        return null;
    }

    private void fillWhenNull(MetaObject metaObject, String fieldName, Object value) {
        if (getFieldValByName(fieldName, metaObject) == null) {
            setFieldValByName(fieldName, value, metaObject);
        }
    }
}
