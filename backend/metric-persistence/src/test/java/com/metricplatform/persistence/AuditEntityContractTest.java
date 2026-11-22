package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.entity.BaseEntity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

class AuditEntityContractTest {

    @Test
    void everyEntityInheritsTheAuditedFields() throws Exception {
        var root =
                Path.of(
                        System.getProperty("basedir"),
                        "src/main/java",
                        BaseEntity.class.getPackageName().replace('.', '/'));
        var tables = new HashSet<String>();
        try (var files = Files.list(root)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var name = file.getFileName().toString().replace(".java", "");
                var entity = Class.forName(BaseEntity.class.getPackageName() + "." + name);
                if (Modifier.isAbstract(entity.getModifiers())) continue;
                assertThat(entity).isAssignableTo(BaseEntity.class);
                var mapping = entity.getDeclaredAnnotation(TableName.class);
                assertThat(mapping).as(name).isNotNull();
                assertThat(mapping.value()).isNotBlank();
                assertThat(tables.add(mapping.value())).as(mapping.value()).isTrue();
                assertThat(entity.getDeclaredFields())
                        .extracting("name")
                        .doesNotContain(
                                "tenantId", "createdAt", "updatedAt", "deleted", "deletedAt");
            }
        }
        assertThat(tables).isNotEmpty();

        assertThat(
                        BaseEntity.class
                                .getDeclaredField("createdAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.INSERT);
        assertThat(
                        BaseEntity.class
                                .getDeclaredField("updatedAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.INSERT_UPDATE);
        TableLogic logic =
                BaseEntity.class.getDeclaredField("deleted").getAnnotation(TableLogic.class);
        assertThat(logic.value()).isEqualTo("false");
        assertThat(logic.delval()).isEqualTo("true");
        assertThat(
                        BaseEntity.class
                                .getDeclaredField("deletedAt")
                                .getAnnotation(TableField.class)
                                .value())
                .isEqualTo("deleted_at");
        assertThat(
                        BaseEntity.class
                                .getDeclaredField("deletedAt")
                                .getAnnotation(TableField.class)
                                .fill())
                .isEqualTo(FieldFill.UPDATE);
    }
}
