package com.metricplatform.dbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只在读取时把已发布 dbt 原生定义转换为页面卡片，不落展示模型。 */
public final class DbtArtifactPresenter {
    private static final String MANIFEST = "https://schemas.getdbt.com/dbt/manifest/v12.json";
    private static final String SEMANTIC = "dbt-metricflow/0.15.0";
    private static final String CATALOG = "https://schemas.getdbt.com/dbt/catalog/v1.json";
    private static final String METRIC = "METRIC", DIMENSION = "DIMENSION";
    private static final String SEMANTIC_MODEL = "SEMANTIC_MODEL", TABLE = "TABLE", VIEW = "VIEW";
    private static final String CATALOG_META = "_platformCatalog";
    private final ObjectMapper mapper = new ObjectMapper();

    public record Section(String title, Map<String, JsonNode> fields) {}

    public record ResourcePresentation(
            UUID releaseId, String resourceId, String kind, String title,
            List<Section> sections, JsonNode nativeDetails, boolean queryable) {}

    public ResourcePresentation present(
            UUID releaseId, String resourceId, String kind, String title,
            String definitionJson, boolean queryable, Map<String, String> schemas) {
        if (schemas == null || !MANIFEST.equals(schemas.get("manifest"))
                || !SEMANTIC.equals(schemas.get("semanticManifest"))
                || !CATALOG.equals(schemas.get("catalog")))
            throw new IllegalArgumentException("dbt 产物版本不兼容");
        try {
            JsonNode definition = mapper.readTree(definitionJson);
            if (definition == null || !definition.isObject())
                throw new IllegalArgumentException("dbt 原生定义无效");
            ObjectNode nativeDetails = ((ObjectNode) definition).deepCopy();
            JsonNode catalogMetadata = nativeDetails.remove(CATALOG_META);
            List<Section> sections = new ArrayList<>();
            if (METRIC.equals(kind)) {
                sections.add(section("指标", nativeDetails,
                        "type", "label", "description", "type_params", "filter"));
            } else if (DIMENSION.equals(kind)) {
                sections.add(section("维度", nativeDetails,
                        "type", "type_params", "expr", "label", "description"));
            } else if (SEMANTIC_MODEL.equals(kind)) {
                sections.add(section("语义模型", nativeDetails,
                        "entities", "dimensions", "measures", "defaults", "model"));
            } else if (TABLE.equals(kind) || VIEW.equals(kind)) {
                sections.add(section("物理关系", catalogMetadata,
                        "relation", "columns"));
                sections.add(section("模型", nativeDetails,
                        "config", "columns", "description", "depends_on"));
            } else {
                sections.add(new Section("原生属性", Map.of()));
            }
            return new ResourcePresentation(
                    releaseId, resourceId, kind, title, sections, nativeDetails,
                    queryable && List.of(METRIC, DIMENSION, SEMANTIC_MODEL, TABLE, VIEW).contains(kind));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("dbt 原生定义无效", error);
        }
    }

    private static Section section(String title, JsonNode source, String... keys) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (source != null) for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && !value.isNull()) fields.put(key, value);
        }
        return new Section(title, fields);
    }
}
