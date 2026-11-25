package com.metricplatform.catalog.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.QueryRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 显式拒绝旧字段与别名；未知输入不能在 BFF 反序列化时被忽略。 */
public final class BuildRequestParser {
    private static final String MODE = "mode",
            METRICS = "metrics",
            SELECTOR = "selector",
            LIMIT = "limit",
            GROUP_BY = "groupBy",
            NAME = "name",
            BINDINGS = "bindings",
            OPTION_ID = "optionId",
            GRAIN = "grain",
            FILTERS = "filters",
            START_TIME = "startTime",
            END_TIME = "endTime",
            ORDER_BY = "orderBy",
            DIMENSION_OPTION = "dimensionOptionId",
            PREVIEW = "PREVIEW",
            DIMENSION_VALUES = "DIMENSION_VALUES",
            QUERY = "QUERY",
            EXPLAIN = "EXPLAIN";
    private static final Pattern BOUND_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int MAX_SELECTIONS = 100;
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
        var node =
                MAPPER.reader()
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .readTree(body);
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
        if (node.has(GROUP_BY)) validateGroups(node);
    }

    private static void validateGroups(JsonNode request) {
        var groups = request.path(GROUP_BY);
        if (!groups.isArray() || groups.size() > MAX_SELECTIONS)
            throw new IllegalArgumentException("groupBy 必须是最多含 100 项的数组");
        Set<String> aliases = new HashSet<>();
        for (var group : groups) {
            if (!group.has(BINDINGS) && !group.has(NAME)) {
                validateOption(group);
                continue;
            }
            // 绑定必须按原始指标名精确覆盖；拒绝重复键、缺项和会隐式转为字符串的值。
            var metrics = request.path(METRICS);
            var bindings = group.path(BINDINGS);
            var name = group.path(NAME).textValue();
            if (name == null || !BOUND_NAME.matcher(name).matches() || !aliases.add(name))
                throw new IllegalArgumentException("绑定输出列名称无效或重复");
            if (!metrics.isArray()
                    || metrics.isEmpty()
                    || metrics.size() > MAX_SELECTIONS
                    || !bindings.isObject()
                    || bindings.size() != metrics.size())
                throw new IllegalArgumentException("绑定必须覆盖全部所选指标");
            Set<String> selected = new HashSet<>();
            for (var metric : metrics) {
                var key = metric.textValue();
                if (key == null
                        || key.isBlank()
                        || !selected.add(key)
                        || key.equals(name)
                        || !bindings.has(key))
                    throw new IllegalArgumentException("指标名必须唯一、具有对应绑定且与输出列名不同");
                validateOption(bindings.path(key));
            }
        }
    }

    private static void validateOption(JsonNode option) {
        var id = option.path(OPTION_ID).textValue();
        if (id == null
                || id.isBlank()
                || (option.hasNonNull(GRAIN) && !option.path(GRAIN).isTextual()))
            throw new IllegalArgumentException("维度选项必须提供有效 optionId 和字符串粒度");
    }
}
