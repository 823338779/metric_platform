package com.metricplatform.query.api;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.api.BuildRequestParser;
import com.metricplatform.query.QueryService;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.TenantAssets;
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
    public static final String ROOT = "/api/dbt";
    public static final String SUBMIT = "/builds/{buildId}/queries";

    /** 绑定登录身份的查询用例。 */
    private final QueryService service;

    private final TenantAssets assets;

    @PostMapping(QueryController.SUBMIT)
    public ResponseEntity<ResultPage> submit(
            @PathVariable UUID buildId,
            @RequestBody String request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        principal.requireManager();
        assets.requireBuild(buildId);
        return ResponseEntity.ok()
                .body(
                        service.submit(
                                buildId,
                                BuildRequestParser.parse(request, QueryRequest.class),
                                principal.identityId()));
    }
}
