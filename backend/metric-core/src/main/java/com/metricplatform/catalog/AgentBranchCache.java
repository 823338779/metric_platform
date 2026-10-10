package com.metricplatform.catalog;

import com.metricplatform.catalog.AgentProjectContracts.BranchList;

/** 明确标记观察时间的离线历史分支快照。 */
public interface AgentBranchCache {
    void save(String projectId, BranchList branches);

    BranchList find(String projectId);
}
