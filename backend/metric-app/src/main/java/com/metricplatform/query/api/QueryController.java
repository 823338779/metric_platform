package com.metricplatform.query.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.query.QueryService;
import com.metricplatform.security.PlatformPrincipal;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 查询身份仍属于平台登录用户，执行状态与分页完全由服务给出。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(QueryController.ROOT)
public class QueryController {
    public static final String ROOT = "/api/v3/dbt";
    public static final String SUBMIT = "/builds/{buildId}/queries";
    public static final String STATUS = "/queries/{queryId}/status";
    public static final String RESULTS = "/queries/{queryId}/results";

    /** 与引擎结果页契约一致，不限制查询自身的行数上限。 */
    private static final int MAX_RESULT_PAGE_SIZE = 200;

    /** 绑定登录身份的查询用例。 */
    private final QueryService service;

    /** 服务权威读取与查询端口。 */
    private final BuildGateway gateway;

    @PostMapping(QueryController.SUBMIT)
    public ResponseEntity<QueryView> submit(
            @PathVariable UUID buildId,
            @RequestBody String request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.accepted()
                .body(
                        service.submit(
                                buildId,
                                com.metricplatform.catalog.api.BuildRequestParser.parse(
                                        request, QueryRequest.class),
                                principal.identityId()));
    }

    @GetMapping(QueryController.STATUS)
    public QueryView status(@PathVariable UUID queryId) {
        return gateway.queryStatus(queryId);
    }

    @GetMapping(QueryController.RESULTS)
    public ResultPage results(
            @PathVariable UUID queryId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_RESULT_PAGE_SIZE) {
            throw new IllegalArgumentException("结果分页要求 offset >= 0 且 limit 在 1 到 200 之间");
        }
        return gateway.queryResults(queryId, offset, limit);
    }
}
