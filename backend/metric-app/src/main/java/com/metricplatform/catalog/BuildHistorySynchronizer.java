package com.metricplatform.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每次从已提交水位补拉；服务故障不影响已有历史展示。 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class BuildHistorySynchronizer {
    private static final int PAGE_SIZE = 200;
    private static final String SYNC_FAILURE = "构建历史同步暂不可用，保留上次提交水位: {}";

    /** 服务权威读取与查询端口。 */
    private final BuildGateway gateway;

    /** 可重建的本地历史投影。 */
    private final BuildHistoryRepository history;

    public void syncOnce() {
        var cursor = history.cursor();
        history.apply(gateway.changes(cursor, PAGE_SIZE), cursor);
    }

    @Scheduled(
            fixedDelayString = "${metric-platform.build-history.poll-ms:30000}",
            initialDelayString = "${metric-platform.build-history.poll-ms:30000}")
    public void scheduledSync() {
        try {
            syncOnce();
        } catch (RuntimeException e) {
            log.warn(SYNC_FAILURE, e.getClass().getSimpleName());
        }
    }
}
