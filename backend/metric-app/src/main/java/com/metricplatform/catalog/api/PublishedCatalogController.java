package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 固定构建的目录及同步可用维度门面，不提供发布动作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(PublishedCatalogController.ROOT)
public class PublishedCatalogController {
    public static final String ROOT = "/api/dbt";
    private static final String BUILD = "/builds/{buildId}",
            RESOURCE = BUILD + "/resources/{resourceId}";
    private static final String CATALOG = "/catalog",
            RESOURCE_VIEW = "/{view:source|lineage|native-details}",
            AVAILABLE_DIMENSIONS = "/available-dimensions",
            EMPTY = "";

    /** 服务权威的固定构建目录与可用维度接口。 */
    private final BuildGateway gateway;

    @GetMapping(BUILD + CATALOG)
    public CatalogPage catalog(
            @PathVariable UUID buildId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return gateway.catalog(buildId, new CatalogFilter(q, kind, cursor, limit));
    }

    @GetMapping(RESOURCE)
    public ResourceView resource(@PathVariable UUID buildId, @PathVariable String resourceId) {
        return gateway.resource(buildId, resourceId, EMPTY);
    }

    @GetMapping(RESOURCE + RESOURCE_VIEW)
    public ResourceView view(
            @PathVariable UUID buildId,
            @PathVariable String resourceId,
            @PathVariable String view) {
        return gateway.resource(buildId, resourceId, view);
    }

    @PostMapping(BUILD + AVAILABLE_DIMENSIONS)
    public ResponseEntity<AvailableDimensionsView> availableDimensions(
            @PathVariable UUID buildId, @RequestBody String request) {
        return ResponseEntity.ok()
                .body(
                        gateway.availableDimensions(
                                buildId,
                                BuildRequestParser.parse(
                                        request, AvailableDimensionsRequest.class)));
    }
}
