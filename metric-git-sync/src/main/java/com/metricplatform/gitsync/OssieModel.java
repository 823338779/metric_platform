package com.metricplatform.gitsync;

import java.util.List;

/** 校验后的协议快照；仅承载逻辑身份和结构化口径，不依赖平台数据库类型。 */
public record OssieModel(
        String name,
        List<Dataset> datasets,
        List<Metric> metrics,
        List<Relationship> relationships,
        String description) {
    public OssieModel(
            String name,
            List<Dataset> datasets,
            List<Metric> metrics,
            List<Relationship> relationships) {
        this(name, datasets, metrics, relationships, null);
    }

    public record Dataset(
            String name,
            String source,
            String description,
            String displayName,
            String defaultTimeField,
            List<String> primaryKey,
            List<String> synonyms,
            List<Field> fields) {}

    public record Field(
            String name,
            String column,
            String description,
            String displayName,
            Role role,
            String unit,
            List<String> synonyms) {}

    public record Metric(
            String name,
            String dataset,
            String field,
            String aggregation,
            String description,
            String displayName,
            String unit,
            List<String> synonyms,
            List<Predicate> predicates) {}

    /** field 为该指标所属数据集内的逻辑字段名；values 只含字符串、数字或布尔值。 */
    public record Predicate(String field, String operator, List<Object> values) {}

    /** 关联两侧 column 是物理列名；指标表达式则引用逻辑字段名。 */
    public record Relationship(
            String name,
            String from,
            String to,
            String fromColumn,
            String toColumn,
            String joinType) {}

    public enum Role {
        MEASURE,
        DIMENSION,
        TIME
    }
}
