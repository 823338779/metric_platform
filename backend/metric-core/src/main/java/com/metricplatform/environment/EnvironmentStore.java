package com.metricplatform.environment;

import com.metricplatform.environment.EnvironmentContracts.*;

import java.util.List;
import java.util.UUID;

/** 平台环境配置持久端口，不复制服务构建事实。 */
public interface EnvironmentStore {
    EnvironmentView create(EnvironmentInput input);

    EnvironmentView update(UUID id, long expectedVersion, EnvironmentInput input);

    EnvironmentView get(UUID id);

    List<EnvironmentView> list();
}
