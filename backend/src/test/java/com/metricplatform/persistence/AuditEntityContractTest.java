package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.entity.BaseAuditEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditEntityContractTest {
    private static final List<Class<?>> ENTITIES =
            List.of(AuditEventEntity.class, PlatformUserEntity.class);

    @Test
    void everyEntityInheritsTheAuditedFields() throws Exception {
        assertThat(ENTITIES).allMatch(BaseAuditEntity.class::isAssignableFrom);

        assertThat(
                        BaseAuditEntity.class
                                .getDeclaredField("createdAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.INSERT);
        assertThat(
                        BaseAuditEntity.class
                                .getDeclaredField("updatedAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.INSERT_UPDATE);
        TableLogic logic =
                BaseAuditEntity.class.getDeclaredField("deleted").getAnnotation(TableLogic.class);
        assertThat(logic.value()).isEqualTo("false");
        assertThat(logic.delval()).isEqualTo("true");
        assertThat(
                        BaseAuditEntity.class
                                .getDeclaredField("deletedAt")
                                .getAnnotation(TableField.class)
                                .value())
                .isEqualTo("deleted_at");
        assertThat(
                        BaseAuditEntity.class
                                .getDeclaredField("deletedAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.UPDATE);
    }
}
