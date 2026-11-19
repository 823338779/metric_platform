package com.metricplatform.environment;

import com.metricplatform.environment.EnvironmentContracts.*;

import java.util.UUID;

/** 保存一次提交的固定请求；网络重试不重新读取当前环境。 */
public interface BuildSubmissionStore {
    SnapshotBuildRequest reserve(
            String tenant, UUID actor, UUID environment, SubmitBuildCommand command);

    void accepted(UUID actor, String key, UUID buildId);
}
