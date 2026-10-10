package com.metricplatform.catalog;

import java.util.List;

/** Agent 项目及实时分支读取协议。 */
public final class AgentProjectContracts {
    private AgentProjectContracts() {}

    public record ProjectView(
            /** Agent 既有项目身份。 */
            String projectId,
            /** 用户可见项目名称。 */
            String name,
            /** 项目配置乐观锁版本。 */
            long version,
            /** 是否已配置开发仓库和执行绑定。 */
            boolean developmentConfigured,
            /** 已配置的规范仓库地址；普通项目可为空。 */
            String repository) {}

    public record ProjectPage(
            /** 本页可见项目。 */
            List<ProjectView> items,
            /** Agent 返回的后续分页游标。 */
            String nextCursor) {}

    public record BranchView(
            /** Git 分支短名。 */
            String branchName,
            /** 此次读取观察到的远端提交。 */
            String headSha) {}

    public record BranchList(
            /** 此次观察的真实分支集合。 */
            List<BranchView> items,
            /** 最近成功读取 Agent 的时间，缓存降级时保持原值。 */
            String observedAt,
            /** 是否使用失联后的历史观察。 */
            boolean cached,
            /** AGENT 或 AGENT_CACHE 来源标志。 */
            String source) {}
}
