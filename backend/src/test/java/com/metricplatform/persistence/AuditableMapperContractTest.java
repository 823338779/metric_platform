package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.persistence.mapper.AuditEventMapper;
import com.metricplatform.persistence.mapper.AuditableBaseMapper;
import com.metricplatform.persistence.mapper.PlatformUserMapper;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditableMapperContractTest {
    @Test
    void everyMapperUsesTheAuditableBaseContract() throws Exception {
        assertThat(List.of(AuditEventMapper.class, PlatformUserMapper.class))
                .allMatch(AuditableBaseMapper.class::isAssignableFrom);
        assertThat(AuditableBaseMapper.class.getMethod("softDeleteById", Serializable.class))
                .isNotNull();
    }

    @Test
    void mapperSqlIsDeclaredInXmlInsteadOfAnnotations() {
        Set<String> sqlAnnotations = Set.of("Select", "Insert", "Update", "Delete");
        List<Class<?>> mappers = List.of(AuditEventMapper.class, PlatformUserMapper.class);

        List<String> violations =
                mappers.stream()
                        .flatMap(mapper -> java.util.Arrays.stream(mapper.getDeclaredMethods()))
                        .flatMap(
                                method ->
                                        java.util.Arrays.stream(method.getAnnotations())
                                                .filter(
                                                        annotation ->
                                                                sqlAnnotations.contains(
                                                                        annotation
                                                                                .annotationType()
                                                                                .getSimpleName()))
                                                .map(Annotation::annotationType)
                                                .map(
                                                        annotation ->
                                                                method.getDeclaringClass()
                                                                                .getSimpleName()
                                                                        + "#"
                                                                        + method.getName()
                                                                        + " @"
                                                                        + annotation
                                                                                .getSimpleName()))
                        .toList();

        assertThat(violations).isEmpty();
    }
}
