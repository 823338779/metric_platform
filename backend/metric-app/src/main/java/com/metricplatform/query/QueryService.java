package com.metricplatform.query;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 查询绑定指定构建并保留登录身份；平台不重新解释引擎选择。 */
@Service
@RequiredArgsConstructor
public class QueryService {
    /** 服务权威读取与查询端口。 */
    private final BuildGateway gateway;

    /** 受理查询的用户归因存储。 */
    private final QueryAuditRepository audit;

    public ResultPage submit(UUID buildId, QueryRequest request, UUID userId) {
        // 新请求确认服务的实时能力，历史投影不能作为执行许可。
        gateway.getBuild(buildId);
        var query = gateway.query(buildId, request);
        if (!buildId.equals(query.buildId())) throw new IllegalStateException("服务返回了不同构建的查询");
        // 本地审计身份仅供归因，不作为服务查询身份返回或用于结果读取。
        audit.record(UUID.randomUUID(), buildId, userId);
        return query;
    }
}
