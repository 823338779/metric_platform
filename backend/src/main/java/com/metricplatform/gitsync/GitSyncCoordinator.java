package com.metricplatform.gitsync;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** webhook 与补偿轮询共用单线程消费路径；数据库检查点负责跨实例并发隔离。 */
@RequiredArgsConstructor
@Slf4j
public class GitSyncCoordinator implements GitRefreshTrigger {
    private static final String THREAD = "metric-git-sync",
            INVALID = "INVALID_PROTOCOL",
            FAILED = "SYNC_FAILED";
    private final GitSnapshotReader reader;
    private final OssieParser parser;
    private final GitImportPort importer;
    private final Duration interval;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name(THREAD).factory());
    private final AtomicBoolean queued = new AtomicBoolean();

    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(
                this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void requestRefresh() {
        if (queued.compareAndSet(false, true)) {
            try {
                executor.execute(
                        () -> {
                            queued.set(false);
                            refresh();
                        });
            } catch (RejectedExecutionException e) {
                queued.set(false);
                throw e;
            }
        }
    }

    private void refresh() {
        String expected = null;
        try {
            expected = importer.state().appliedCommit();
            var snapshot = reader.read();
            if (!Objects.equals(expected, snapshot.commit()))
                importer.apply(expected, snapshot, parser.parse(snapshot.yaml()));
            else importer.healthy(expected);
        } catch (Exception e) {
            // 异常不得逃出周期任务，否则 ScheduledExecutor 会永久停止后续补偿。
            // 不记录底层异常文本或堆栈，Git/数据源驱动可能在其中携带凭据。
            log.warn("Git 同步失败，异常类型={}", e.getClass().getSimpleName());
            try {
                importer.failed(
                        expected,
                        e instanceof ProtocolException ? INVALID : FAILED,
                        e instanceof ProtocolException
                                ? e.getMessage()
                                : "同步失败，请检查 Git、数据源、定义冲突和平台日志");
            } catch (Exception ignored) {
                /* 数据库恢复后下一次周期仍会重试；Git 是持久的目标状态。 */
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
