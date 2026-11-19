package com.metricplatform.environment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.environment.*;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.TenantAssets;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 环境构建与历史只调用服务 HTTP API。 */
@RestController
@RequestMapping("/api/dbt")
public class EnvironmentBuildController {
    private static final String ITEMS = "items",
            NEXT_CURSOR = "nextCursor",
            BUILD_ID = "buildId",
            HISTORY_SCOPE = "environment-builds";

    /** 平台提交用例。 */
    private final EnvironmentBuildService builds;

    /** 环境读取与存在性核验。 */
    private final TenantAssets assets;

    /** 构建事实读取。 */
    private final EnvironmentBuildGateway gateway;

    public EnvironmentBuildController(
            EnvironmentBuildService builds, TenantAssets assets, EnvironmentBuildGateway gateway) {
        this.builds = builds;
        this.assets = assets;
        this.gateway = gateway;
    }

    @PostMapping("/environments/{environmentId}/builds")
    public ResponseEntity<String> submit(
            @PathVariable UUID environmentId,
            @RequestBody SubmitBuildCommand command,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return json(builds.submit(environmentId, command, principal), HttpStatus.ACCEPTED);
    }

    @GetMapping("/environments/{environmentId}/builds")
    public ResponseEntity<String> history(
            @PathVariable UUID environmentId,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String buildStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("页大小必须在 1 到 200 之间");
        var page =
                assets.<JsonNode>history(
                        environmentId,
                        cursor,
                        next -> {
                            var raw =
                                    gateway.history(
                                            new EnvironmentBuildFilter(
                                                    environmentId,
                                                    repository,
                                                    branchName,
                                                    buildStatus,
                                                    next,
                                                    limit));
                            var items = new ArrayList<JsonNode>();
                            raw.required(ITEMS).forEach(items::add);
                            return new TenantAssets.Page<>(
                                    items, raw.path(NEXT_CURSOR).asText(null));
                        },
                        item ->
                                assets.visible(
                                        environmentId,
                                        UUID.fromString(item.required(BUILD_ID).asText())),
                        HISTORY_SCOPE,
                        repository,
                        branchName,
                        buildStatus);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return json(mapper.valueToTree(page), HttpStatus.OK);
    }

    @GetMapping("/builds/{buildId}")
    public ResponseEntity<String> get(@PathVariable UUID buildId) {
        assets.requireBuild(buildId);
        return json(gateway.getBuild(buildId), HttpStatus.OK);
    }

    @GetMapping("/builds/{buildId}/execution-logs")
    public ResponseEntity<String> logs(
            @PathVariable UUID buildId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("页大小必须在 1 到 200 之间");
        assets.requireBuild(buildId);
        return json(gateway.logs(buildId, cursor, limit), HttpStatus.OK);
    }

    private static ResponseEntity<String> json(JsonNode value, HttpStatus status) {
        // Jackson 2 节点不交给 MVC 的 Jackson 3 当 Java Bean 序列化，保留服务字段和数值精度。
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(value.toString());
    }
}
