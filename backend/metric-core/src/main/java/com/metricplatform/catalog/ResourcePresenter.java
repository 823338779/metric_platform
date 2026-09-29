package com.metricplatform.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将引擎原生产物转换为平台资源详情；具体格式由引擎适配器解释。 */
public interface ResourcePresenter {
    record Section(String title, Map<String, JsonNode> fields) {}

    record ResourcePresentation(
            UUID releaseId,
            String resourceId,
            String kind,
            String title,
            List<Section> sections,
            JsonNode nativeDetails,
            boolean queryable) {}

    ResourcePresentation present(
            UUID releaseId,
            String resourceId,
            String kind,
            String title,
            String definitionJson,
            boolean queryable,
            Map<String, String> schemas);
}
