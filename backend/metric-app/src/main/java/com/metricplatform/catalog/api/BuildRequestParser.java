package com.metricplatform.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.QueryRequest;
import java.util.Set;

/** 显式拒绝旧字段与别名；未知输入不能在 BFF 反序列化时被忽略。 */
public final class BuildRequestParser {
    private static final String MODE = "mode",
            METRICS = "metrics",
            SELECTOR = "selector",
            LIMIT = "limit",
            GROUP_BY = "groupBy",
            FILTERS = "filters",
            START_TIME = "startTime",
            END_TIME = "endTime",
            ORDER_BY = "orderBy",
            DIMENSION_OPTION = "dimensionOptionId",
            PREVIEW = "PREVIEW",
            DIMENSION_VALUES = "DIMENSION_VALUES",
            QUERY = "QUERY",
            EXPLAIN = "EXPLAIN";
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .USE_BIG_INTEGER_FOR_INTS);

    private BuildRequestParser() {}

    public static <T> T parse(String body, Class<T> type) {
        try {
            if (type == QueryRequest.class) validateModeFields(body);
            T value = MAPPER.readValue(body, type);
            if (value == null) throw new IllegalArgumentException("请求不能为 null");
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("请求字段或类型不符合执行快照契约");
        }
    }

    private static void validateModeFields(String body) throws java.io.IOException {
        // 必须检查原始字段存在性：与模式无关的 null/空数组也不能被省略后悄悄接受。
        var node = MAPPER.readTree(body);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("查询必须为对象");
        String mode = node.path(MODE).asText();
        Set<String> allowed =
                switch (mode) {
                    case PREVIEW -> Set.of(MODE, SELECTOR, LIMIT);
                    case DIMENSION_VALUES ->
                            Set.of(
                                    MODE,
                                    METRICS,
                                    FILTERS,
                                    START_TIME,
                                    END_TIME,
                                    LIMIT,
                                    DIMENSION_OPTION);
                    case QUERY, EXPLAIN ->
                            Set.of(
                                    MODE,
                                    METRICS,
                                    FILTERS,
                                    START_TIME,
                                    END_TIME,
                                    LIMIT,
                                    GROUP_BY,
                                    ORDER_BY);
                    default -> throw new IllegalArgumentException("查询模式无效");
                };
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) throw new IllegalArgumentException("查询字段不适用于当前模式");
        }
        if (PREVIEW.equals(mode)
                && (!node.path(SELECTOR).isTextual() || node.path(SELECTOR).asText().isBlank()))
            throw new IllegalArgumentException("预览 selector 不能为空");
    }
}
