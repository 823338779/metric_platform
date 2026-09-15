package com.metricplatform.gitsync;

/** 可被 Git 管理的表白名单；不得从 YAML 直接传入动态 SQL 标识符。 */
public enum GitObjectKind {
    DATASET("dataset"),
    FIELD("dataset_field"),
    DIMENSION("dimension"),
    METRIC("metric"),
    RELATIONSHIP("dataset_relationship");
    private final String table;

    GitObjectKind(String table) {
        this.table = table;
    }

    public String table() {
        return table;
    }

    public static GitObjectKind fromTable(String table) {
        for (var kind : values()) if (kind.table.equals(table)) return kind;
        throw new IllegalArgumentException("unsupported Git object kind");
    }
}
