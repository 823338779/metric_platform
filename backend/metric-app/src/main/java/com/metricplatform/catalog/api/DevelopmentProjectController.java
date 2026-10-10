package com.metricplatform.catalog.api;

import com.metricplatform.catalog.*;
import com.metricplatform.catalog.AgentProjectContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 项目/真实分支由 Agent 管理，失联仅允许带时间标记的缓存展示。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(DevelopmentProjectController.ROOT)
public class DevelopmentProjectController {
    public static final String ROOT = "/api/v3/dbt/projects";
    public static final String BRANCHES = "/{agentProjectId}/branches";

    /** 服务权威读取与查询端口。 */
    private final AgentProjectGateway gateway;

    /** 带来源时间的分支历史快照。 */
    private final AgentBranchCache cache;

    @GetMapping
    public ProjectPage projects(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return gateway.listProjects(cursor, limit);
    }

    @GetMapping(DevelopmentProjectController.BRANCHES)
    public BranchList branches(@PathVariable String agentProjectId) {
        try {
            var result = gateway.listBranches(agentProjectId);
            cache.save(agentProjectId, result);
            return result;
        } catch (BuildGateway.Failure e) {
            if (e.status() < 500) throw e;
            var cached = cache.find(agentProjectId);
            if (cached == null) throw e;
            return cached;
        }
    }
}
