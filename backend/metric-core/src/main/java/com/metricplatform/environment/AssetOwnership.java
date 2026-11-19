package com.metricplatform.environment;

import java.util.UUID;

/** 平台只保存归属与分页范围，构建状态由服务读取。 */
public interface AssetOwnership {
    boolean hasEnvironment(String tenant, UUID environment);

    boolean hasBuild(String tenant, UUID build);

    boolean hasBuild(String tenant, UUID environment, UUID build);

    UUID cursorSecret(String tenant);
}
