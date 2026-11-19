package com.metricplatform.catalog.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.security.TenantAssets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    private final TenantAssets assets;

    @GetMapping(BuildHistoryController.BUILDS)
    public BuildPage list(
            @RequestParam UUID environmentId,
            @RequestParam(required = false) String repository,
            @RequestParam(required = false) String buildStatus,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        var page =
                assets.<BuildView>history(
                        environmentId,
                        cursor,
                        next -> {
                            var raw =
                                    gateway.listBuilds(
                                            new BuildFilter(
                                                    environmentId,
                                                    repository,
                                                    branchName,
                                                    buildStatus,
                                                    next,
                                                    limit));
                            return new TenantAssets.Page<>(raw.items(), raw.nextCursor());
                        },
                        item -> assets.visible(environmentId, item.buildId()),
                        BUILDS,
                        repository,
                        branchName,
                        buildStatus);
        return new BuildPage(page.items(), page.nextCursor());
    }

    @GetMapping(BuildHistoryController.CURRENT)
    public DeploymentTargetView current(
            @RequestParam String repository,
            @RequestParam UUID environmentId,
            @RequestParam String branchName) {
        assets.requireEnvironment(environmentId);
        var current = gateway.current(new DeploymentKey(repository, environmentId, branchName));
        if (!environmentId.equals(current.environmentId())
                || current.activeBuildId() != null
                        && !assets.visible(environmentId, current.activeBuildId())
                || current.latestAttempt() != null
                        && !assets.visible(environmentId, current.latestAttempt().buildId()))
            throw TenantAssets.missing();
        return current;
    }

    @GetMapping(BuildHistoryController.DEPLOYMENTS)
    public DeploymentPage deployments(
            @RequestParam String repository,
            @RequestParam UUID environmentId,
            @RequestParam(required = false) String branchName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        var page =
                assets.<DeploymentAttemptView>history(
                        environmentId,
                        cursor,
                        next -> {
                            var raw =
                                    gateway.listDeployments(
                                            new DeploymentFilter(
                                                    repository,
                                                    environmentId,
                                                    branchName,
                                                    next,
                                                    limit));
                            return new TenantAssets.Page<>(raw.items(), raw.nextCursor());
                        },
                        item -> assets.visible(environmentId, item.buildId()),
                        DEPLOYMENTS,
                        repository,
                        branchName);
        return new DeploymentPage(page.items(), page.nextCursor());
    }
}
