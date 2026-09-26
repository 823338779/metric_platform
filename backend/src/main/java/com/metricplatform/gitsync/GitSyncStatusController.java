package com.metricplatform.gitsync;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 状态仅供平台运维查看，沿用 /api/v1 的身份认证，不影响 Agent 的 push 完成边界。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "metric-platform.git-sync", name = "enabled", havingValue = "true")
@ConditionalOnExpression("'${metric-platform.git-sync.mode:OSSIE}' == 'OSSIE'")
public class GitSyncStatusController {
    private static final String PATH = "/api/v1/git-sync/status";
    private final GitImportPort importer;

    @GetMapping(PATH)
    public GitImportPort.State status() {
        return importer.state();
    }
}
