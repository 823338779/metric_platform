package com.metricplatform.catalog;

import com.metricplatform.catalog.AgentProjectContracts.*;

/** 项目及 Git 分支只向 Agent 读取。 */
public interface AgentProjectGateway {
    ProjectPage listProjects(String cursor, int limit);

    BranchList listBranches(String agentProjectId);
}
