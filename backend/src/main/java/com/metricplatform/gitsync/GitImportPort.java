package com.metricplatform.gitsync;

import java.time.Instant;

/** Git 调度与平台事务发布的边界；expectedCommit 是旧任务隔离的比较基线。 */
public interface GitImportPort {
    State state();

    boolean apply(String expectedCommit, GitSnapshot snapshot, OssieModel model);

    void failed(String expectedCommit, String code, String message);

    /** 已追上远端时按检查点清除瞬时错误；不得清除另一实例新记录的版本状态。 */
    void healthy(String expectedCommit);

    /** appliedCommit 仅在整个快照成功发布后改变，失败状态不能冒充生效版本。 */
    record State(
            String repositoryId,
            String appliedCommit,
            String modelName,
            String errorCode,
            String errorMessage,
            Instant updatedAt) {}
}
