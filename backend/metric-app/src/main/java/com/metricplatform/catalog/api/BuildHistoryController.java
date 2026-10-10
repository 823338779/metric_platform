package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.catalog.BuildHistoryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 本地历史仅供展示，构建详情和当前部署始终读取服务。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(BuildHistoryController.ROOT)
public class BuildHistoryController {
    public static final String ROOT = "/api/v3/dbt";
    public static final String BUILDS = "/builds";
    public static final String BUILD = "/builds/{buildId}";
    public static final String CURRENT = "/deployments/current";
    public static final String DEPLOYMENTS = "/deployments";

    /** 服务权威读取与查询端口。 */
    private final BuildGateway gateway;

    /** 可重建的本地历史投影。 */
    private final BuildHistoryRepository history;

    @GetMapping(BuildHistoryController.BUILDS)
    public BuildPage list(
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return history.list(new BuildFilter(repository, branchName, cursor, limit));
    }

    @GetMapping(BuildHistoryController.BUILD)
    public BuildView build(@PathVariable UUID buildId) {
        return gateway.getBuild(buildId);
    }

    @GetMapping(BuildHistoryController.CURRENT)
    public DeploymentTargetView current(
            @RequestParam String repository,
            @RequestParam Environment environment,
            @RequestParam String branchName) {
        return gateway.current(new DeploymentKey(repository, environment, branchName));
    }

    @GetMapping(BuildHistoryController.DEPLOYMENTS)
    public DeploymentPage deployments(
            @RequestParam String repository,
            @RequestParam(required = false) Environment environment,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return gateway.listDeployments(
                new DeploymentFilter(repository, environment, branchName, cursor, limit));
    }
}
