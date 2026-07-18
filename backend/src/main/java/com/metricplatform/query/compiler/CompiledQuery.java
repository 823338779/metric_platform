package com.metricplatform.query.compiler;

import java.util.List;

public record CompiledQuery(String sql, List<SqlParameter> parameters) {
    public CompiledQuery {
        parameters = List.copyOf(parameters);
    }

    public record SqlParameter(int jdbcType, Object value) {}
}
