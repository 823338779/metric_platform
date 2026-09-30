package com.metricplatform.engine.dbt.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.catalog.PublishedCatalogGateway;
import java.util.Set;

/** 检查显示协议结构，不解析原生 manifest 或推导业务资源。 */
final class PublishedProtocol {
    private static final String INVALID = "invalid_runtime_response",
            RELEASE = "releaseId",
            PROJECT = "projectId",
            ACTIVE = "activePublication",
            KIND = "kind",
            ATTRIBUTES = "attributes";
    private static final Set<String> KINDS =
            Set.of("METRIC", "DIMENSION", "SEMANTIC_MODEL", "TABLE", "VIEW");

    private PublishedProtocol() {}

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
