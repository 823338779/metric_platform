package com.metricplatform.catalog;

import com.metricplatform.catalog.BuildContracts.*;

import java.util.UUID;

/** 只读构建及版本固定查询端口，发布仅由 Agent 调用服务。 */
public interface BuildGateway {
    BuildView getBuild(UUID buildId);

    BuildPage listBuilds(BuildFilter filter);

    DeploymentTargetView current(DeploymentKey key);

    DeploymentPage listDeployments(DeploymentFilter filter);

    CatalogPage catalog(UUID buildId, CatalogFilter filter);

    ResourceView resource(UUID buildId, String resourceId, String view);

    OptionsTaskView options(UUID buildId, OptionsRequest request);

    OptionsTaskView getOptions(UUID optionsTaskId);

    QueryView query(UUID buildId, QueryRequest request);

    QueryView queryStatus(UUID queryId);

    ResultPage queryResults(UUID queryId, int offset, int limit);

    ChangePage changes(String cursor, int limit);

    /** 保留服务状态与业务诊断，禁止错误时回退旧协议。 */
    final class Failure extends RuntimeException {
        /** 原始上游 HTTP 状态。 */
        private final int status;
        /** 稳定业务错误诊断。 */
        private final ErrorView error;

        public Failure(int status, ErrorView error) {
            super(error.message());
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public ErrorView error() {
            return error;
        }
    }
}
