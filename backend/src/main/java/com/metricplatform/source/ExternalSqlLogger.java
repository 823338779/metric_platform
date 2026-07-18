package com.metricplatform.source;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ExternalSqlLogger {
    private static final String UNAVAILABLE = "[unavailable]";
    private static final Pattern SENSITIVE_URL_PROPERTY =
            Pattern.compile("(?i)(^|[?&;])((?:password|pwd|pass|secret|token)=)[^&;]*");
    private static final Pattern SENSITIVE_URL_USER_INFO =
            Pattern.compile("(?i)(://[^/@:]+:)[^/@]+@");

    private ExternalSqlLogger() {}

    public static void log(
            Connection connection,
            String operation,
            UUID dataSourceId,
            String database,
            String sql,
            List<Parameter> parameters) {
        List<Parameter> values = parameters == null ? List.of() : List.copyOf(parameters);
        try {
            RenderedSql rendered = render(sql, values);
            DatabaseMetaData metadata = connection.getMetaData();
            String runtimeDatabase =
                    first(database, connection.getCatalog(), connection.getSchema());
            log.info(
                    "external_sql operation={} dataSourceId={} jdbcUrl={} database={} username={} sqlTemplate={} parameters={} completeSql={} reconstructionComplete={}",
                    safe(operation),
                    safe(dataSourceId),
                    sanitizeJdbcUrl(metadata.getURL()),
                    safe(runtimeDatabase),
                    safe(metadata.getUserName()),
                    safeSql(sql),
                    parameterSummary(values),
                    safeSql(rendered.sql()),
                    rendered.complete());
        } catch (Exception ignored) {
            fallback(operation, dataSourceId, database, sql, values);
        }
    }

    public static void logRaw(
            Connection connection,
            String operation,
            UUID dataSourceId,
            String database,
            String sql) {
        log(connection, operation, dataSourceId, database, sql, List.of());
    }

    public static String renderCompleteSql(String sql, List<Parameter> parameters) {
        List<Parameter> values = parameters == null ? List.of() : List.copyOf(parameters);
        return render(sql, values).sql();
    }

    static RenderedSql render(String sql, List<Parameter> parameters) {
        if (sql == null) return new RenderedSql(UNAVAILABLE, parameters.isEmpty());
        StringBuilder rendered = new StringBuilder(sql.length() + parameters.size() * 16);
        State state = State.NORMAL;
        int parameterIndex = 0;
        boolean complete = true;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                if (current == '\'') state = State.SINGLE_QUOTE;
                else if (current == '"') state = State.DOUBLE_QUOTE;
                else if (current == '-' && next == '-') state = State.LINE_COMMENT;
                else if (current == '/' && next == '*') state = State.BLOCK_COMMENT;
                else if (current == '?') {
                    if (parameterIndex < parameters.size()) {
                        rendered.append(literal(parameters.get(parameterIndex++)));
                    } else {
                        rendered.append(current);
                        complete = false;
                    }
                    continue;
                }
            } else if (state == State.SINGLE_QUOTE && current == '\'') {
                if (next == '\'') {
                    rendered.append(current).append(next);
                    index++;
                    continue;
                }
                state = State.NORMAL;
            } else if (state == State.DOUBLE_QUOTE && current == '"') {
                if (next == '"') {
                    rendered.append(current).append(next);
                    index++;
                    continue;
                }
                state = State.NORMAL;
            } else if (state == State.LINE_COMMENT && (current == '\n' || current == '\r')) {
                state = State.NORMAL;
            } else if (state == State.BLOCK_COMMENT && current == '*' && next == '/') {
                rendered.append(current).append(next);
                index++;
                state = State.NORMAL;
                continue;
            }
            rendered.append(current);
        }
        return new RenderedSql(
                rendered.toString(), complete && parameterIndex == parameters.size());
    }

    static String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return UNAVAILABLE;
        String sanitized = SENSITIVE_URL_PROPERTY.matcher(jdbcUrl).replaceAll("$1$2[redacted]");
        return SENSITIVE_URL_USER_INFO.matcher(sanitized).replaceAll("$1[redacted]@");
    }

    private static String literal(Parameter parameter) {
        Object value = parameter.value();
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof byte[] bytes) return "X'" + HexFormat.of().formatHex(bytes) + "'";
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String parameterSummary(List<Parameter> parameters) {
        return IntStream.range(0, parameters.size())
                .mapToObj(
                        index ->
                                (index + 1)
                                        + ":"
                                        + jdbcTypeName(parameters.get(index).jdbcType())
                                        + "="
                                        + literal(parameters.get(index)))
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String jdbcTypeName(int jdbcType) {
        try {
            return JDBCType.valueOf(jdbcType).getName();
        } catch (IllegalArgumentException ignored) {
            return String.valueOf(jdbcType);
        }
    }

    private static void fallback(
            String operation,
            UUID dataSourceId,
            String database,
            String sql,
            List<Parameter> parameters) {
        try {
            log.info(
                    "external_sql operation={} dataSourceId={} jdbcUrl={} database={} username={} sqlTemplate={} parameters={} completeSql=[reconstruction-failed] reconstructionComplete=false",
                    safe(operation),
                    safe(dataSourceId),
                    UNAVAILABLE,
                    safe(database),
                    UNAVAILABLE,
                    safeSql(sql),
                    parameterSummary(parameters));
        } catch (RuntimeException ignored) {
            // Observability must never block the external SQL operation.
        }
    }

    private static String safeSql(String value) {
        return safe(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String safe(Object value) {
        return value == null ? UNAVAILABLE : String.valueOf(value);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public record Parameter(int jdbcType, Object value) {}

    record RenderedSql(String sql, boolean complete) {}

    private enum State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
