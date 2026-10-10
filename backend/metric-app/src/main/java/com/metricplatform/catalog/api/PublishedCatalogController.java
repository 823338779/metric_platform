package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 固定构建的目录及异步选项门面，不提供发布动作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(PublishedCatalogController.ROOT)
public class PublishedCatalogController {
    public static final String ROOT = "/api/v3/dbt";
    private static final String BUILD = "/builds/{buildId}",
            RESOURCE = BUILD + "/resources/{resourceId}";
    private static final String CATALOG = "/catalog",
            RESOURCE_VIEW = "/{view:source|lineage|native-details}",
            OPTIONS = "/query-options",
            OPTIONS_TASK = OPTIONS + "/{optionsTaskId}",
            EMPTY = "";

    /** 服务权威的固定构建目录与选项接口。 */
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

    @PostMapping(BUILD + OPTIONS)
    public ResponseEntity<OptionsTaskView> options(
            @PathVariable UUID buildId, @RequestBody String request) {
        return ResponseEntity.accepted()
                .body(
                        gateway.options(
                                buildId, BuildRequestParser.parse(request, OptionsRequest.class)));
    }

    @GetMapping(OPTIONS_TASK)
    public OptionsTaskView options(@PathVariable UUID optionsTaskId) {
        return gateway.getOptions(optionsTaskId);
    }
}
