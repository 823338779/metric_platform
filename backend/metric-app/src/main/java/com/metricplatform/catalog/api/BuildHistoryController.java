package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 构建历史、构建详情和当前部署始终读取服务。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(BuildHistoryController.ROOT)
public class BuildHistoryController {
    public static final String ROOT = "/api/dbt";
    public static final String BUILDS = "/builds";
    public static final String CURRENT = "/deployments/current";
    public static final String DEPLOYMENTS = "/deployments";

    /** 服务权威读取与查询端口。 */
    private final BuildGateway gateway;

    @GetMapping(BuildHistoryController.BUILDS)
    public BuildPage list(
            @RequestParam UUID environmentId,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String buildStatus,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        return gateway.listBuilds(
                new BuildFilter(environmentId, repository, branchName, buildStatus, cursor, limit));
    }

    @GetMapping(BuildHistoryController.CURRENT)
    public DeploymentTargetView current(
            @RequestParam String repository,
            @RequestParam UUID environmentId,
            @RequestParam String branchName) {
        return gateway.current(new DeploymentKey(repository, environmentId, branchName));
    }

    @GetMapping(BuildHistoryController.DEPLOYMENTS)
    public DeploymentPage deployments(
            @RequestParam String repository,
            @RequestParam UUID environmentId,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return gateway.listDeployments(
                new DeploymentFilter(repository, environmentId, branchName, cursor, limit));
    }
}
