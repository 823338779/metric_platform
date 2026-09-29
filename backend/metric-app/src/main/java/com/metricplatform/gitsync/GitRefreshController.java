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

    /** 当前部署的 Git 同步开关。 */
    private final GitSyncProperties properties;

    /** 同步关闭时协调器不存在，仍保留接口以返回明确的不可用原因。 */
    private final ObjectProvider<GitRefreshTrigger> triggers;

    @PostMapping(PATH)
    public ResponseEntity<?> refresh() {
        var trigger = triggers.getIfAvailable();
        if (!properties.isEnabled() || trigger == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            ProblemDetail.forStatusAndDetail(
                                    HttpStatus.SERVICE_UNAVAILABLE, "Git 同步未开启，无法手动刷新"));
        }
        // 只入队刷新信号，继续由单线程协调器执行 Git 读取和发布校验。
        trigger.requestRefresh();
        return ResponseEntity.accepted().body(Map.of(ACCEPTED, true));
    }
}
