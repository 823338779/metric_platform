package com.metricplatform.catalog;

import com.metricplatform.environment.EnvironmentContracts.BuildExecutionSnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 执行服务 的固定构建协议；不包含平台发布操作。 */
public final class BuildContracts {
    private BuildContracts() {}

    public enum BuildStatus {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        OUTCOME_UNKNOWN
    }

    public enum DeploymentStatus {
        WAITING_FOR_BUILD,
        PENDING,
        CHECKING,
        DEPLOYED,
        STALE,
        FAILED,
        CANCELLED
    }

    public enum QueryMode {
        QUERY,
        EXPLAIN,
        PREVIEW,
        DIMENSION_VALUES
    }

    public record ErrorView(
            /** 稳定错误码。 */
            String code,
            /** 不含凭据或本地路径的诊断。 */
            String message,
            /** 能否以同请求重试。 */
            boolean retryable,
            /** 失败执行阶段。 */
            String phase,
            /** 原生详情所属构建。 */
            UUID buildId) {}

    public record BuildView(
            /** 原生详情所属构建。 */
            UUID buildId,
            /** 规范仓库地址。 */
            String repository,
            /** 目标分支短名。 */
            String branchName,
            /** 受理时显式指定的 SHA。 */
            String requestedCommitSha,
            /** 固定源码版本。 */
            String commitSha,
            /** 受理时固定的完整执行快照。 */
            BuildExecutionSnapshot execution,
            /** 固定工具链版本。 */
            String toolchainVersion,
            /** 构建状态，不代表部署状态。 */
            BuildStatus buildStatus,
            /** 失败执行阶段。 */
            String phase,
            /** 意图受理时间。 */
            String createdAt,
            /** 最近事实更新时间。 */
            String updatedAt,
            /** 终态时间。 */
            String finishedAt,
            /** 完整封存目录是否可读。 */
            boolean catalogAvailable,
            /** 稳定错误。 */
            ErrorView error,
            /** 受理时附带的部署意图。 */
            Map<String, Object> initialDeployment) {}

    public record BuildFilter(
            /** 必填外部环境身份，隔离历史及分页游标。 */
            UUID environmentId,
            /** 规范仓库地址。 */
            String repository,
            /** 目标分支短名。 */
            String branchName,
            /** 可选构建状态筛选。 */
            String buildStatus,
            /** 当前筛选条件的分页位置。 */
            String cursor,
            /** 本次读取的最大条数。 */
            int limit) {}

    public record BuildPage(
            /** 构建历史列表。 */
            List<BuildView> items,
            /** 后续分页游标。 */
            String nextCursor,
            /** 观察来源，实时服务不使用本地投影标志。 */
            String source,
            /** 平台最近原子同步时间。 */
            String syncedAt) {
        public BuildPage(List<BuildView> items, String nextCursor) {
            this(items, nextCursor, null, null);
        }
    }

    public record DeploymentKey(
            /** 规范仓库地址。 */
            String repository,
            /** 物理执行环境。 */
            UUID environmentId,
            /** 目标分支短名。 */
            String branchName) {}

    public record DeploymentFilter(
            /** 规范仓库地址。 */
            String repository,
            /** 物理执行环境。 */
            UUID environmentId,
            /** 目标分支短名。 */
            String branchName,
            /** 当前筛选条件的分页位置。 */
            String cursor,
            /** 本次读取的最大条数。 */
            int limit) {}

    public record DeploymentAttemptView(
            /** 规范仓库地址。 */
            String repository,
            /** 物理执行环境。 */
            UUID environmentId,
            /** 目标分支短名。 */
            String branchName,
            /** 受理顺序，不按完成时间分配。 */
            long generation,
            /** 原生详情所属构建。 */
            UUID buildId,
            /** 独立于构建的部署状态。 */
            DeploymentStatus deploymentStatus,
            /** 未部署或过期原因。 */
            String reason,
            /** 意图受理时间。 */
            String createdAt,
            /** 实际切换时间。 */
            String deployedAt) {}

    public record DeploymentTargetView(
            /** 规范仓库地址。 */
            String repository,
            /** 物理执行环境。 */
            UUID environmentId,
            /** 目标分支短名。 */
            String branchName,
            /** 目标乐观锁版本。 */
            long version,
            /** 最新受理意图序号。 */
            long desiredGeneration,
            /** 当前实际生效构建。 */
            UUID activeBuildId,
            /** 当前实际生效提交。 */
            String activeCommitSha,
            /** 最新部署意图。 */
            DeploymentAttemptView latestAttempt,
            /** 最近观察远端 head。 */
            String observedHeadSha,
            /** 远端观察时间。 */
            String headObservedAt,
            /** 远端观察结果，不替代部署终态。 */
            String sourceState) {}

    public record DeploymentPage(
            /** 当前筛选条件下的有界结果。 */
            List<DeploymentAttemptView> items,
            /** 绑定筛选条件的后续分页游标。 */
            String nextCursor) {}

    public record ResourceSource(
            /** 构建封存的工程相对路径。 */
            String path,
            /** 构建封存的完整源码正文。 */
            String content) {}

    public record ResourceView(
            /** 原生节点与源码所属的固定构建。 */
            UUID buildId,
            /** dbt 原生 unique_id。 */
            String resourceId,
            /** 完整原生节点，保留引擎扩展字段及数值精度。 */
            Map<String, Object> resource,
            /** 无源码路径时为空，不从工作目录猜测源码。 */
            ResourceSource source) {}

    public record SelectedDimension(
            /** 过滤选项身份。 */
            String optionId,
            /** 引擎支持的时间粒度。 */
            String grain) {}

    public record SelectedFilter(
            /** 过滤选项身份。 */
            String optionId,
            /** 选项允许的过滤操作。 */
            String operator,
            /** 保留 JSON 类型的过滤值。 */
            Object value) {}

    public record SelectedOrder(
            /** 已选择的指标或分组选项。 */
            String fieldId,
            /** 排序方向。 */
            String direction) {}

    public record AvailableDimensionsRequest(
            /** 规范化指标集合。 */
            List<String> metrics) {}

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record QueryRequest(
            /** 查询模式。 */
            QueryMode mode,
            /** 规范化指标集合。 */
            List<String> metrics,
            /** 有序分组。 */
            List<SelectedDimension> groupBy,
            /** 类型化过滤条件。 */
            List<SelectedFilter> filters,
            /** 起点，按构建业务时区解释。 */
            String startTime,
            /** 终点，按构建业务时区解释。 */
            String endTime,
            /** 有序排序条件。 */
            List<SelectedOrder> orderBy,
            /** 本次读取的最大条数。 */
            Integer limit,
            /** PREVIEW 的 dbt 原生选择表达式。 */
            String selector,
            /** DIMENSION_VALUES 的维度选项。 */
            String dimensionOptionId) {}

    public record AvailableDimensionsView(
            /** 原生详情所属构建。 */
            UUID buildId,
            /** 规范化指标集合。 */
            List<String> metrics,
            /** 当前指标共同支持的可用维度。 */
            List<Map<String, Object>> dimensions) {}

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ResultPage(
            /** 原生详情所属构建。 */
            UUID buildId,
            /** 查询模式。 */
            QueryMode mode,
            /** 固定源码版本。 */
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            String commitSha,
            /** 构建固定的业务时区。 */
            String businessTimezone,
            /** 归一化时间输入。 */
            Map<String, String> normalizedTimeRange,
            /** EXPLAIN 返回的完整编译 SQL。 */
            String sql,
            /** 结果列及精度类型。 */
            List<Map<String, Object>> columns,
            /** 有界结果正文。 */
            List<List<Object>> rows,
            /** 引擎结果是否截断。 */
            Boolean resultTruncated) {}
}
