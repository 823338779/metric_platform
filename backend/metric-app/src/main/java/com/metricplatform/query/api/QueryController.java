package com.metricplatform.query.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.query.QueryService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 同步查询保留平台登录归因，完整结果由服务返回。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(QueryController.ROOT)
public class QueryController {
    public static final String ROOT = "/api/v3/dbt";
    public static final String SUBMIT = "/builds/{buildId}/queries";

    /** 绑定登录身份的查询用例。 */
    private final QueryService service;

    @PostMapping(QueryController.SUBMIT)
    public ResponseEntity<ResultPage> submit(
            @PathVariable UUID buildId,
            @RequestBody String request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok()
                .body(
                        service.submit(
                                buildId,
                                com.metricplatform.catalog.api.BuildRequestParser.parse(
                                        request, QueryRequest.class),
                                principal.identityId()));
    }
}
