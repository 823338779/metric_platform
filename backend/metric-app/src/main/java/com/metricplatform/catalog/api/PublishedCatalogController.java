package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.security.TenantAssets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 固定构建的目录及同步可用维度门面，不提供发布动作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(PublishedCatalogController.ROOT)
public class PublishedCatalogController {
    public static final String ROOT = "/api/dbt";
    private static final String BUILD = "/builds/{buildId}",
            RESOURCE = BUILD + "/resources/{resourceId}";
    private static final String LINEAGE = "/lineage";

    /** 服务权威的固定构建目录与可用维度接口。 */
    private final BuildGateway gateway;

    private final TenantAssets assets;

    @GetMapping(RESOURCE)
    public ResourceView resource(@PathVariable UUID buildId, @PathVariable String resourceId) {
        assets.requireTenantBuild(buildId);
        return gateway.resource(buildId, resourceId);
    }

    @GetMapping(BUILD + LINEAGE)
    public ResponseEntity<String> lineage(
            @PathVariable UUID buildId, @RequestParam String selector) {
        assets.requireTenantBuild(buildId);
        if (selector.isBlank()) throw new IllegalArgumentException("血缘 selector 不能为空");
        return nativeJson(gateway.lineage(buildId, selector));
    }

    private static ResponseEntity<String> nativeJson(
            com.fasterxml.jackson.databind.JsonNode value) {
        // MVC 使用 Jackson 3，直接输出 Jackson 2 原生 JSON，避免节点内省破坏结构。
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(value.toString());
    }
}
