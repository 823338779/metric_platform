package com.metricplatform.catalog.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricplatform.catalog.CatalogReadRepository;
import com.metricplatform.catalog.PublishedCatalogGateway;
import com.metricplatform.engine.EngineClient;
import com.metricplatform.query.QueryRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** v1 调用方继续使用旧响应形状；新目录和查询只经过服务发布权威。 */
@RestController
@ConditionalOnProperty(name = "metric.publication.mode", havingValue = "service-proxy")
@RequiredArgsConstructor
@RequestMapping("/api/v1/semantic-projects")
public class PublishedCompatibilityController {
    private static final String RELEASE_ID = "releaseId",
            PROJECT_ID = "projectId",
            RESOURCE_ID = "resourceId",
            ACTIVE = "activePublication",
            SOURCE_SHA = "sourceSha",
            RESOURCES = "resources",
            PAGE = "page",
            CAPABILITIES = "capabilities",
            QUERYABLE = "queryable",
            NATIVE = "nativeDetails",
            DEFINITION = "definitionJson",
            EMPTY = "",
            STATE = "state",
            STATUS = "status",
            REQUEST = "request",
            MODE = "mode",
            KEY = "idempotencyKey",
            METRICS = "metrics",
            QUERY = "QUERY",
            EXPLAIN = "EXPLAIN",
            PREVIEW = "PREVIEW",
            VALUES = "DIMENSION_VALUES",
            QUERIES = "queries",
            OPTIONS = "query-options";
    private final PublishedCatalogGateway gateway;
    private final CatalogReadRepository history;
    private final QueryRepository historicalQueries;
    private final ObjectProvider<EngineClient> historicalRuntime;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @GetMapping
    public ResponseEntity<String> projects() {
        var output = mapper.createArrayNode();
        for (var project : gateway.projects()) {
            var active = project.path(ACTIVE);
            var row = output.addObject();
            row.set(PROJECT_ID, project.get(PROJECT_ID));
            row.set("activeReleaseId", active.path(RELEASE_ID));
            row.set("observedSha", active.path(SOURCE_SHA));
            row.putNull("lastObservationStatus");
            row.putNull("lastObservedAt");
        }
        return json(output);
    }

    @GetMapping("/{projectId}/releases")
    public ResponseEntity<String> releases(@PathVariable String projectId) {
        var output = mapper.createArrayNode();
        var seen = new java.util.HashSet<String>();
        for (var release : gateway.releases(projectId)) {
            output.add(legacyRelease(release));
            seen.add(release.path(RELEASE_ID).asText());
        }
        // 旧记录仅作为只读历史补充，不参与活动目录解析。
        for (var old : history.releases(projectId))
            if (!seen.contains(old.releaseId().toString())) output.add(mapper.valueToTree(old));
        return json(output);
    }

    @GetMapping("/{projectId}/releases/{releaseId}")
    public ResponseEntity<String> release(
            @PathVariable String projectId, @PathVariable UUID releaseId) {
        try {
            return json(legacyRelease(gateway.release(projectId, releaseId)));
        } catch (PublishedCatalogGateway.Failure error) {
            if (error.status() != 404) throw error;
            return json(mapper.valueToTree(history.release(projectId, releaseId)));
        }
    }

    @GetMapping("/{projectId}/catalog")
    public ResponseEntity<String> catalog(
            @PathVariable String projectId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page == Integer.MAX_VALUE) throw new IllegalArgumentException("目录页码无效");
        var result =
                (ObjectNode)
                        gateway.catalog(
                                        projectId,
                                        fixed(projectId, releaseId),
                                        q,
                                        kind,
                                        page + 1,
                                        size)
                                .deepCopy();
        var resources = mapper.createArrayNode();
        result.path(RESOURCES).forEach(item -> resources.add(legacyResource(item)));
        result.set(RESOURCES, resources);
        result.put(PAGE, page);
        return json(result);
    }

    @GetMapping("/{projectId}/resources/{resourceId}")
    public ResponseEntity<String> resource(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId) {
        return json(
                legacyResource(
                        gateway.resource(
                                projectId, fixed(projectId, releaseId), resourceId, EMPTY)));
    }

    @GetMapping("/{projectId}/resources/{resourceId}/lineage")
    public ResponseEntity<String> lineage(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("血缘数量无效");
        var result =
                (ObjectNode)
                        gateway.resource(
                                        projectId,
                                        fixed(projectId, releaseId),
                                        resourceId,
                                        "lineage")
                                .deepCopy();
        var edges = mapper.createArrayNode();
        result.path("dependencies")
                .forEach(
                        item -> {
                            if (edges.size() < limit) edges.add(item);
                        });
        result.set("dependencies", edges);
        return json(result);
    }

    @GetMapping("/{projectId}/resources/{resourceId}/source")
    public ResponseEntity<String> source(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        var source = gateway.resource(projectId, releaseId, resourceId, "source");
        var result = mapper.createObjectNode();
        result.set(RELEASE_ID, source.get(RELEASE_ID));
        result.set("sourcePath", source.get("path"));
        result.set("content", source.get("content"));
        result.set("buildSourceSha", gateway.release(projectId, releaseId).get(SOURCE_SHA));
        return json(result);
    }

    @GetMapping("/{projectId}/resources/{resourceId}/presentation")
    public ResponseEntity<String> presentation(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        var resource = gateway.resource(projectId, releaseId, resourceId, EMPTY);
        var result = mapper.createObjectNode();
        for (var field : List.of(RELEASE_ID, RESOURCE_ID, "kind", NATIVE))
            result.set(field, resource.get(field));
        result.set("title", resource.get("displayName"));
        result.put(QUERYABLE, resource.path(CAPABILITIES).size() > 0);
        var section = result.putArray("sections").addObject();
        section.put("title", "已发布属性");
        section.set("fields", resource.get("attributes"));
        return json(result);
    }

    @GetMapping("/{projectId}/query-options")
    public ResponseEntity<String> options(
            @PathVariable String projectId,
            @RequestParam UUID releaseId,
            @RequestParam List<String> metrics) {
        var body = mapper.createObjectNode().put(RELEASE_ID, releaseId.toString());
        body.set(METRICS, mapper.valueToTree(metrics));
        return json(gateway.compatibility(projectId, OPTIONS, body));
    }

    @PostMapping("/{projectId}/queries")
    public ResponseEntity<String> query(@PathVariable String projectId, @RequestBody String body) {
        return submit(projectId, QUERY, body, null);
    }

    @PostMapping("/{projectId}/queries:explain")
    public ResponseEntity<String> explain(
            @PathVariable String projectId, @RequestBody String body) {
        return submit(projectId, EXPLAIN, body, null);
    }

    @PostMapping("/{projectId}/queries:dimension-values")
    public ResponseEntity<String> values(@PathVariable String projectId, @RequestBody String body) {
        return submit(projectId, VALUES, body, null);
    }

    @PostMapping("/{projectId}/datasets/{resourceId}:preview")
    public ResponseEntity<String> preview(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestBody String body) {
        return submit(projectId, PREVIEW, body, resourceId);
    }

    @GetMapping("/{projectId}/queries/{queryId}")
    public ResponseEntity<String> queryStatus(
            @PathVariable String projectId, @PathVariable UUID queryId) {
        ObjectNode result;
        try {
            result = (ObjectNode) gateway.query(projectId, queryId).deepCopy();
        } catch (PublishedCatalogGateway.Failure error) {
            if (error.status() != 404) throw error;
            return historicalQuery(projectId, queryId);
        }
        result.put(
                STATUS,
                switch (result.path(STATE).asText()) {
                    case "READY" -> "COMPLETED";
                    case "QUEUED" -> "PENDING";
                    default -> result.path(STATE).asText();
                });
        result.remove(STATE);
        return json(result);
    }

    private ResponseEntity<String> historicalQuery(String projectId, UUID queryId) {
        // 迁移前受理的查询只读取原运行结果，不补写或重新提交平台任务。
        var record = historicalQueries.find(projectId, queryId);
        var release = history.release(projectId, record.releaseId());
        var runtime = historicalRuntime.getIfAvailable();
        if (runtime == null) throw new PublishedCatalogGateway.Failure(503, "runtime_unavailable");
        var snapshot =
                record.runtimeQueryId() == null
                        ? runtime.getQueryByKey(record.idempotencyKey())
                        : runtime.getQuery(record.runtimeQueryId());
        ObjectNode result =
                snapshot == null ? mapper.createObjectNode() : mapper.valueToTree(snapshot);
        result.put("queryId", queryId.toString());
        result.put(RELEASE_ID, record.releaseId().toString());
        result.put("targetCommitSha", release.targetCommitSha());
        result.put(MODE, record.mode());
        String state = snapshot == null ? record.status() : snapshot.state();
        result.put(
                STATUS,
                switch (state) {
                    case "READY" -> "COMPLETED";
                    case "QUEUED" -> "PENDING";
                    default -> state;
                });
        result.remove(STATE);
        return json(result);
    }

    private UUID fixed(String projectId, UUID requested) {
        if (requested != null) return requested;
        var value = gateway.publication(projectId).path(ACTIVE).path(RELEASE_ID);
        if (!value.isTextual()) throw new PublishedCatalogGateway.Failure(404, "not_found");
        return UUID.fromString(value.asText());
    }

    private JsonNode legacyResource(JsonNode resource) {
        var result = mapper.createObjectNode();
        for (var field :
                List.of(RESOURCE_ID, "nativeId", "kind", "name", "description", "sourcePath"))
            result.set(field, resource.get(field));
        result.put(DEFINITION, resource.path(NATIVE).toString());
        result.put(QUERYABLE, resource.path(CAPABILITIES).size() > 0);
        return result;
    }

    private JsonNode legacyRelease(JsonNode release) {
        var result = mapper.createObjectNode();
        for (var field :
                List.of(RELEASE_ID, PROJECT_ID, STATE, "errorCode", "createdAt", "publishedAt"))
            result.set(field, release.get(field));
        result.set("targetCommitSha", release.get(SOURCE_SHA));
        result.set("buildSourceSha", release.get(SOURCE_SHA));
        result.put("cleanupState", "PENDING");
        result.putNull("schemaName");
        result.putNull("projectDigest");
        return result;
    }

    private ResponseEntity<String> submit(
            String projectId, String mode, String body, String resourceId) {
        try {
            var request =
                    mapper.createObjectNode()
                            .put(MODE, mode)
                            .put(KEY, UUID.randomUUID().toString());
            request.set(REQUEST, mapper.readTree(body));
            if (resourceId != null) request.put(RESOURCE_ID, resourceId);
            return ResponseEntity.accepted()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(gateway.compatibility(projectId, QUERIES, request).toString());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("查询参数无效");
        }
    }

    private ResponseEntity<String> json(JsonNode value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(value.toString());
    }
}
