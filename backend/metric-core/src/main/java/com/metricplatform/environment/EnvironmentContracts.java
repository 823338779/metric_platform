package com.metricplatform.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.List;
import java.util.UUID;

/** 环境只描述物理执行目标，源码信息仅存在于单次构建请求。 */
public final class EnvironmentContracts {
    private EnvironmentContracts() {}

    public record ConnectionTarget(
            /** dbt 适配器类型。 */
            String type,
            /** 基础设施核验的物理实例身份。 */
            String instanceId,
            /** 网络数据库主机。 */
            String host,
            /** 网络数据库端口。 */
            Integer port,
            /** DuckDB 受控文件路径。 */
            String path) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("连接只接受声明的非敏感字段");
        }
    }

    public record ExecutionSnapshot(
            /** 外部环境稳定身份。 */
            UUID environmentId,
            /** 本次固定配置版本。 */
            long configVersion,
            /** 非敏感物理连接信息。 */
            ConnectionTarget connection,
            /** 服务允许的凭据引用。 */
            String credentialRef,
            /** 目标 catalog。 */
            String catalog,
            /** 目标 schema。 */
            String schema,
            /** dbt 并行度。 */
            int threads,
            /** 查询业务时区。 */
            String businessTimezone) {}

    public record EnvironmentInput(
            /** 展示名称。 */
            String name,
            /** 完整非敏感连接。 */
            ConnectionTarget connection,
            /** secret 引用。 */
            String credentialRef,
            /** 目标 catalog。 */
            String catalog,
            /** 目标 schema。 */
            String schema,
            /** dbt 并行度。 */
            int threads,
            /** 业务时区。 */
            String businessTimezone) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("环境不接受未声明字段或源码绑定");
        }

        public ExecutionSnapshot snapshot(UUID id, long version) {
            return new ExecutionSnapshot(
                    id,
                    version,
                    connection,
                    credentialRef,
                    catalog,
                    schema,
                    threads,
                    businessTimezone);
        }
    }

    public record EnvironmentView(
            /** 环境 UUID。 */
            UUID environmentId,
            /** 展示名称。 */
            String name,
            /** 当前配置版本。 */
            long configVersion,
            /** 当前执行配置。 */
            ExecutionSnapshot execution) {}

    public record EnvironmentPage(
            /** 有界环境列表。 */
            List<EnvironmentView> items) {}

    public record SubmitBuildCommand(
            /** 必须与服务器全局绑定的源码仓库一致。 */
            String repository,
            /** 本次明确指定的分支。 */
            String branchName,
            /** 可选固定 SHA。 */
            String commitSha,
            /** 用户确认的环境版本。 */
            long expectedConfigVersion,
            /** 构建成功后的部署策略。 */
            String deploymentPolicy,
            /** 同一次提交的稳定键。 */
            String idempotencyKey) {}

    public record SnapshotBuildRequest(
            /** 本次源码仓库。 */
            String repository,
            /** 本次分支。 */
            String branchName,
            /** 可选固定 SHA。 */
            String commitSha,
            /** 后端固定的执行快照。 */
            ExecutionSnapshot execution,
            /** 成功部署策略。 */
            String deploymentPolicy,
            /** 服务幂等键。 */
            String idempotencyKey) {}

    public record EnvironmentBuildFilter(
            /** 环境身份。 */
            UUID environmentId,
            /** 可选仓库筛选。 */
            String repository,
            /** 可选分支筛选。 */
            String branchName,
            /** 可选构建状态。 */
            String buildStatus,
            /** 服务游标。 */
            String cursor,
            /** 页大小。 */
            int limit) {}
}
