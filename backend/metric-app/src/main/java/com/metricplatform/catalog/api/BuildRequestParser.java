package com.metricplatform.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 显式拒绝旧字段与别名；未知输入不能在 BFF 反序列化时被忽略。 */
public final class BuildRequestParser {
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
            T value = MAPPER.readValue(body, type);
            if (value == null) throw new IllegalArgumentException("请求不能为 null");
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("请求字段或类型不符合执行快照契约");
        }
    }
}
