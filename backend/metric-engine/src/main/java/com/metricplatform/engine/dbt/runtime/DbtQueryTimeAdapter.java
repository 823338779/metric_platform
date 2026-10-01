package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 将公开 API 的时刻转换为业务时区日历时间，适配 MetricFlow 无时区边界。 */
final class DbtQueryTimeAdapter {
    /** 当前平台的业务日期口径，与前端日期输入及本地日期型仓库字段一致。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String START_TIME = "startTime";
    private static final String END_TIME = "endTime";

    private DbtQueryTimeAdapter() {}

    static JsonNode normalize(JsonNode request) {
        if (!(request instanceof ObjectNode object)) return request;
        // 只转换出站副本，不改变调用方请求、幂等键或资源身份。
        ObjectNode result = object.deepCopy();
        for (String field : new String[] {START_TIME, END_TIME}) {
            JsonNode value = result.get(field);
            if (value == null || !value.isTextual()) continue;
            try {
                String normalized =
                        OffsetDateTime.parse(value.textValue())
                                .atZoneSameInstant(BUSINESS_ZONE)
                                .toLocalDateTime()
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                result.put(field, normalized);
            } catch (DateTimeParseException ignored) {
                // 无时区或非法输入保持原样，仍由运行服务执行原有校验。
            }
        }
        return result;
    }
}
