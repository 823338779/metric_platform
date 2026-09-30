package com.metricplatform.gitsync;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户可请求同步固定远端，无需持有 Webhook 密钥。 */
@RestController
@RequiredArgsConstructor
public class GitRefreshController {
    private static final String PATH = "/api/v1/semantic-projects:refresh";
    private static final String ACCEPTED = "accepted";

    /** 固定绑定及运行服务配置就绪时提供协调器，与自动同步开关无关。 */
    private final ObjectProvider<GitRefreshTrigger> triggers;

    @PostMapping(PATH)
    public ResponseEntity<?> refresh() {
        var trigger = triggers.getIfAvailable();
        if (trigger == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            ProblemDetail.forStatusAndDetail(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "手动刷新不可用，请配置 Git 仓库、项目绑定并启用 dbt 运行服务"));
        }
        // 只入队刷新信号，继续由单线程协调器执行 Git 读取和发布校验。
        trigger.requestRefresh();
        return ResponseEntity.accepted().body(Map.of(ACCEPTED, true));
    }
}
