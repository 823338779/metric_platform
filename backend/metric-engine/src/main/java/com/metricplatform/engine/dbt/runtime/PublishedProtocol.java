package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.catalog.PublishedCatalogGateway;
import java.util.Set;
import java.util.UUID;

/** 检查显示协议结构，不解析原生 manifest 或推导业务资源。 */
final class PublishedProtocol {
    private static final String INVALID = "invalid_runtime_response",
            RELEASE = "releaseId",
            PROJECT = "projectId",
            ACTIVE = "activePublication",
            KIND = "kind",
            ATTRIBUTES = "attributes";
    private static final String BRANCH = "branchId",
            REF = "gitRef",
            MODE = "mode",
            STATUS = "status";
    private static final Set<String> KINDS =
            Set.of("METRIC", "DIMENSION", "SEMANTIC_MODEL", "TABLE", "VIEW");

    private PublishedProtocol() {}

    /** 分支身份由服务提供；拒绝缺字段响应，避免 UI 将不明目标当作线上。 */
    static JsonNode branch(JsonNode node) {
        text(node, PROJECT, BRANCH, REF, MODE, STATUS);
        try {
            UUID.fromString(node.path(BRANCH).asText());
        } catch (IllegalArgumentException invalid) {
            check(false);
        }
        check(node.path(REF).asText().startsWith("refs/heads/"));
        check(Set.of("PRODUCTION", "PREVIEW").contains(node.path(MODE).asText()));
        check(node.path("version").isIntegralNumber());
        return node;
    }

    /** 请求路径是归属依据，增量字段不会改变既有发布协议。 */
    static JsonNode scoped(JsonNode node, UUID branchId) {
        check(node.isObject() && branchId.toString().equals(node.path(BRANCH).asText()));
        return node;
    }

    static JsonNode publication(JsonNode node) {
        text(node, PROJECT);
        check(node.has(ACTIVE));
        if (!node.path(ACTIVE).isNull()) release(node.path(ACTIVE));
        return node;
    }

    static JsonNode release(JsonNode node) {
        text(node, PROJECT, RELEASE, "state");
        check(node.path("publicationSequence").isIntegralNumber());
        check(node.path("catalogSchemaVersion").asInt(-1) == 1);
        return node;
    }

    static JsonNode catalog(JsonNode node) {
        text(node, RELEASE);
        for (var field : Set.of("page", "size", "total"))
            check(node.path(field).isIntegralNumber());
        check(node.path("resources").isArray());
        node.path("resources").forEach(PublishedProtocol::resource);
        return node;
    }

    static JsonNode resource(JsonNode node) {
        text(node, "resourceId", "nativeId", "name", "displayName", KIND);
        check(KINDS.contains(node.path(KIND).asText()));
        check(node.path("capabilities").isArray() && node.path("nativeDetails").isObject());
        var fields = node.path(ATTRIBUTES);
        check(fields.isObject());
        switch (node.path(KIND).asText()) {
            case "METRIC" -> {
                text(fields, "metricType", "definitionSummary");
                check(
                        fields.path("inputMetricIds").isArray()
                                && fields.path("datasetIds").isArray());
            }
            case "DIMENSION" -> text(fields, "datasetId", "dimensionType");
            case "SEMANTIC_MODEL" -> {
                text(fields, "modelResourceId");
                check(
                        fields.path("dimensionIds").isArray()
                                && fields.path("entities").isArray()
                                && fields.path("measures").isArray());
            }
            default -> {
                check(fields.path("relation").isObject() && fields.path("columns").isObject());
                text(fields.path("relation"), "schema", "identifier");
            }
        }
        return node;
    }

    static JsonNode options(JsonNode node) {
        text(node, RELEASE);
        check(node.path("metricResourceIds").isArray() && node.path("options").isArray());
        node.path("options")
                .forEach(
                        option -> {
                            text(option, "optionId", "displayName");
                            check(
                                    option.path("operators").isArray()
                                            && option.path("granularities").isArray());
                        });
        return node;
    }

    static JsonNode query(JsonNode node) {
        text(node, PROJECT, RELEASE, "queryId", "state");
        return node;
    }

    private static void text(JsonNode node, String... fields) {
        for (var field : fields)
            check(node.path(field).isTextual() && !node.path(field).asText().isBlank());
    }

    private static void check(boolean valid) {
        if (!valid) throw new PublishedCatalogGateway.Failure(502, INVALID);
    }
}
