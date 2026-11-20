package com.metricplatform.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricplatform.catalog.BuildContracts.BuildFilter;
import com.metricplatform.environment.EnvironmentContracts.*;

import java.util.UUID;

/** 版本化服务构建门面，保留服务响应字段及数值精度。 */
public interface EnvironmentBuildGateway {
    JsonNode submit(SnapshotBuildRequest request);

    JsonNode history(BuildFilter filter);

    JsonNode getBuild(UUID buildId);

    JsonNode logs(UUID buildId, String cursor, int limit);
}
