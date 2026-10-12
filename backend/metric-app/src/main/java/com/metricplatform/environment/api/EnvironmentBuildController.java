package com.metricplatform.environment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.environment.*;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 环境构建与历史只调用服务 HTTP API。 */
@RestController
@RequestMapping("/api/v4/dbt")
public class EnvironmentBuildController {
    /** 平台提交用例。 */
    private final EnvironmentBuildService builds;

    /** 环境读取与存在性核验。 */
    private final EnvironmentService environments;

    /** 构建事实读取。 */
    private final EnvironmentBuildGateway gateway;

    public EnvironmentBuildController(
            EnvironmentBuildService builds,
            EnvironmentService environments,
            EnvironmentBuildGateway gateway) {
        this.builds = builds;
        this.environments = environments;
        this.gateway = gateway;
    }

    @PostMapping("/environments/{id}/builds")
    public ResponseEntity<String> submit(
            @PathVariable UUID id,
            @RequestBody SubmitBuildCommand command,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return json(builds.submit(id, command, principal.identityId()), HttpStatus.ACCEPTED);
    }

    @GetMapping("/environments/{id}/builds")
    public ResponseEntity<String> history(
            @PathVariable UUID id,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String buildStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        environments.get(id);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("页大小必须在 1 到 200 之间");
        return json(
                gateway.history(
                        new EnvironmentBuildFilter(
                                id, repository, branchName, buildStatus, cursor, limit)),
                HttpStatus.OK);
    }

    @GetMapping("/builds/{id}")
    public ResponseEntity<String> get(@PathVariable UUID id) {
        return json(gateway.getBuild(id), HttpStatus.OK);
    }

    @GetMapping("/builds/{id}/logs")
    public ResponseEntity<String> logs(
            @PathVariable UUID id, @RequestParam(required = false) String cursor) {
        return json(gateway.logs(id, cursor), HttpStatus.OK);
    }

    private static ResponseEntity<String> json(JsonNode value, HttpStatus status) {
        // Jackson 2 节点不交给 MVC 的 Jackson 3 当 Java Bean 序列化，保留服务字段和数值精度。
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(value.toString());
    }
}
