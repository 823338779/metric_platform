package com.metricplatform.persistence.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.AgentBranchCache;
import com.metricplatform.catalog.AgentProjectContracts.BranchList;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 离线快照只用于历史展示，不对 Git 或部署执行写入。 */
@Repository
public class AgentBranchCacheStore implements AgentBranchCache {
    private static final String SAVE =
            "insert into agent_branch_cache values(?,?::jsonb,?::timestamptz) on"
                + " conflict(agent_project_id) do update set"
                + " response_json=excluded.response_json,observed_at=excluded.observed_at";
    private static final String FIND =
            "select response_json::text from agent_branch_cache where agent_project_id=?";
    /** 平台数据库的参数化 SQL 访问。 */
    private final JdbcTemplate jdbc;
    /** 负责封存 JSON 的类型映射。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentBranchCacheStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String project, BranchList branches) {
        try {
            jdbc.update(SAVE, project, mapper.writeValueAsString(branches), branches.observedAt());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("分支观察无法封存", e);
        }
    }

    public BranchList find(String project) {
        var rows = jdbc.queryForList(FIND, String.class, project);
        if (rows.isEmpty()) return null;
        try {
            var cached = mapper.readValue(rows.getFirst(), BranchList.class);
            return new BranchList(cached.items(), cached.observedAt(), true, "AGENT_CACHE");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("分支快照无效", e);
        }
    }
}
