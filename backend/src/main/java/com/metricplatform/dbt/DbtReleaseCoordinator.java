package com.metricplatform.dbt;

import com.metricplatform.gitsync.GitRefreshTrigger;
import com.metricplatform.gitsync.GitSnapshotReader;
import com.metricplatform.gitsync.GitSyncProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/** 将 Git 触发和定时轮询交给无框架依赖的发布流程。 */
@Slf4j
public final class DbtReleaseCoordinator implements GitRefreshTrigger {
    private static final String THREAD_NAME = "metric-dbt-release";
    private final DbtReleaseWorkflow workflow;
    private final GitSyncProperties properties;
    private final AtomicBoolean refreshRequested = new AtomicBoolean(true);
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, THREAD_NAME);
                        thread.setDaemon(true);
                        return thread;
                    });

    public DbtReleaseCoordinator(
            GitSnapshotReader reader,
            DbtReleaseRepository releases,
            DbtPublicationService publication,
            DbtRuntimeClient runtime,
            GitSyncProperties properties) {
        this.properties = properties;
        this.workflow =
                new DbtReleaseWorkflow(
                        reader,
                        releases,
                        publication,
                        runtime,
                        new DbtReleaseWorkflow.Config(
                                properties.getProjectId(),
                                properties.getRepositoryId(),
                                properties.getProjectSubdir(),
                                properties.getProfileBindingId(),
                                properties.getConfigVersion(),
                                properties.getToolchainVersion()));
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(
                this::tick, 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void requestRefresh() {
        refreshRequested.set(true);
    }

    public void refresh() {
        workflow.refresh();
    }

    public void resumePending() {
        workflow.resumePending();
    }

    private void tick() {
        try {
            workflow.resumePending();
            if (refreshRequested.getAndSet(false)) workflow.refresh();
        } catch (RuntimeException failure) {
            log.warn("dbt release tick failed: {}", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
