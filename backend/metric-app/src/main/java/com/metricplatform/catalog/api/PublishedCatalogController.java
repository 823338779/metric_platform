package com.metricplatform.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.PublishedCatalogGateway;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 认证后的只读目录与查询门面；无本地业务导入或发布写入。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "metric.publication.mode", havingValue = "service-proxy")
@RequestMapping(PublishedCatalogController.ROOT)
public class PublishedCatalogController {
    public static final String ROOT = "/api/v2/semantic-projects";
    private static final String PROJECT = "/{projectId}",
            RELEASES = PROJECT + "/releases",
            RELEASE = RELEASES + "/{releaseId}",
            RESOURCE = RELEASE + "/resources/{resourceId}",
            QUERIES = PROJECT + "/queries",
            EMPTY = "";
    private static final String BRANCHES = PROJECT + "/branches",
            BRANCH = BRANCHES + "/{branchId}",
            BRANCH_RELEASES = BRANCH + "/releases",
            BRANCH_RELEASE = BRANCH_RELEASES + "/{releaseId}",
            BRANCH_RESOURCE = BRANCH_RELEASE + "/resources/{resourceId}",
            BRANCH_QUERIES = BRANCH + "/queries";
    private final PublishedCatalogGateway gateway;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Spring Boot 4 uses Jackson 3; explicit JSON transport avoids bean-serializing Jackson 2
    // nodes.
    private static ResponseEntity<String> json(JsonNode value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(value.toString());
    }

    private static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid JSON request");
        }
    }

    @GetMapping
    public ResponseEntity<String> projects() {
        return json(gateway.projects());
    }

    @GetMapping(PROJECT + "/publication")
    public ResponseEntity<String> publication(@PathVariable String projectId) {
        return json(gateway.publication(projectId));
    }

    @GetMapping(RELEASES)
    public ResponseEntity<String> releases(@PathVariable String projectId) {
        return json(gateway.releases(projectId));
    }

    @GetMapping(RELEASE)
    public ResponseEntity<String> release(
            @PathVariable String projectId, @PathVariable UUID releaseId) {
        return json(gateway.release(projectId, releaseId));
    }

    @GetMapping(RELEASE + "/catalog")
    public ResponseEntity<String> catalog(
            @PathVariable String projectId,
            @PathVariable UUID releaseId,
            @RequestParam(defaultValue = EMPTY) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return json(gateway.catalog(projectId, releaseId, q, kind, page, size));
    }

    @GetMapping(RESOURCE)
    public ResponseEntity<String> resource(
            @PathVariable String projectId,
            @PathVariable UUID releaseId,
            @PathVariable String resourceId) {
        return json(gateway.resource(projectId, releaseId, resourceId, EMPTY));
    }

    @GetMapping(RESOURCE + "/{view:lineage|source|native-details}")
    public ResponseEntity<String> view(
            @PathVariable String projectId,
            @PathVariable UUID releaseId,
            @PathVariable String resourceId,
            @PathVariable String view) {
        return json(gateway.resource(projectId, releaseId, resourceId, view));
    }

    @PostMapping(PROJECT + "/query-options")
    public ResponseEntity<String> options(
            @PathVariable String projectId, @RequestBody String request) {
        return json(gateway.queryOptions(projectId, parse(request)));
    }

    @PostMapping(QUERIES)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<String> submit(
            @PathVariable String projectId, @RequestBody String request) {
        return ResponseEntity.accepted()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gateway.submitQuery(projectId, parse(request)).toString());
    }

    @GetMapping(QUERIES + "/{queryId}")
    public ResponseEntity<String> query(
            @PathVariable String projectId, @PathVariable UUID queryId) {
        return json(gateway.query(projectId, queryId));
    }

    // 平台只代理分支目录与查询，不暴露创建、删除或构建入口。
    @GetMapping(BRANCHES)
    public ResponseEntity<String> branches(@PathVariable String projectId) {
        return json(gateway.branches(projectId));
    }

    @GetMapping(BRANCH)
    public ResponseEntity<String> branch(
            @PathVariable String projectId, @PathVariable UUID branchId) {
        return json(gateway.branch(projectId, branchId));
    }

    @GetMapping(BRANCH + "/publication")
    public ResponseEntity<String> branchPublication(
            @PathVariable String projectId, @PathVariable UUID branchId) {
        return json(gateway.publication(projectId, branchId));
    }

    @GetMapping(BRANCH_RELEASES)
    public ResponseEntity<String> branchReleases(
            @PathVariable String projectId, @PathVariable UUID branchId) {
        return json(gateway.releases(projectId, branchId));
    }

    @GetMapping(BRANCH_RELEASE)
    public ResponseEntity<String> branchRelease(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID releaseId) {
        return json(gateway.release(projectId, branchId, releaseId));
    }

    @GetMapping(BRANCH_RELEASE + "/catalog")
    public ResponseEntity<String> branchCatalog(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID releaseId,
            @RequestParam(defaultValue = EMPTY) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return json(gateway.catalog(projectId, branchId, releaseId, q, kind, page, size));
    }

    @GetMapping(BRANCH_RESOURCE)
    public ResponseEntity<String> branchResource(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID releaseId,
            @PathVariable String resourceId) {
        return json(gateway.resource(projectId, branchId, releaseId, resourceId, EMPTY));
    }

    @GetMapping(BRANCH_RESOURCE + "/{view:lineage|source|native-details}")
    public ResponseEntity<String> branchView(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID releaseId,
            @PathVariable String resourceId,
            @PathVariable String view) {
        return json(gateway.resource(projectId, branchId, releaseId, resourceId, view));
    }

    @GetMapping(BRANCH_RELEASE + "/diff")
    public ResponseEntity<String> branchDiff(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID releaseId) {
        return json(gateway.diff(projectId, branchId, releaseId));
    }

    @PostMapping(BRANCH + "/query-options")
    public ResponseEntity<String> branchOptions(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @RequestBody String request) {
        return json(gateway.queryOptions(projectId, branchId, parse(request)));
    }

    @PostMapping(BRANCH_QUERIES)
    public ResponseEntity<String> branchSubmit(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @RequestBody String request) {
        return ResponseEntity.accepted()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gateway.submitQuery(projectId, branchId, parse(request)).toString());
    }

    @GetMapping(BRANCH_QUERIES + "/{queryId}")
    public ResponseEntity<String> branchQuery(
            @PathVariable String projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID queryId) {
        return json(gateway.query(projectId, branchId, queryId));
    }
}
