package com.metricplatform.gitsync;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 调度端口适配；所有远程探测在发布事务前完成。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "metric-platform.git-sync", name = "enabled", havingValue = "true")
public class GitImportService implements GitImportPort {
    private final GitSyncProperties properties;
    private final GitModelPreflight preflight;
    private final GitPublicationService publisher;

    @Override
    public State state() {
        return publisher.state(properties);
    }

    @Override
    public boolean apply(String expectedCommit, GitSnapshot snapshot, OssieModel model) {
        if (!Objects.equals(state().appliedCommit(), expectedCommit)) return false;
        if (Objects.equals(expectedCommit, snapshot.commit())) {
            healthy(expectedCommit);
            return false;
        }
        var prepared = preflight.prepare(properties, model);
        return publisher.publish(properties, expectedCommit, snapshot.commit(), prepared);
    }

    @Override
    public void failed(String expectedCommit, String code, String message) {
        publisher.failed(properties, expectedCommit, code, message);
    }

    @Override
    public void healthy(String expectedCommit) {
        publisher.healthy(properties, expectedCommit);
    }
}
