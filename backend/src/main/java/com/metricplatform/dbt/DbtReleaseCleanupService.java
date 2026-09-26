package com.metricplatform.dbt;

import com.metricplatform.gitsync.GitSyncProperties;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 按物理 runId 回收无引用 schema，查询受理与清理认领共用 release 行锁。 */
@Service
@ConditionalOnProperty(
        prefix = "metric-platform.dbt-runtime",
        name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DbtReleaseCleanupService {
    private static final String GROUPS =
            "select distinct runtime_run_id from semantic_release where project_id=? and runtime_run_id is not null and cleanup_state<>'CLEANED' order by runtime_run_id";
    private static final String LOCK_GROUP =
            "select release_id,state,cleanup_state from semantic_release where project_id=? and runtime_run_id=? order by release_id for update";
    private static final String ACTIVE =
            "select active_release_id from semantic_project where project_id=? for update";
    private static final String PREVIOUS =
            "select release_id from semantic_release where project_id=? and state='PUBLISHED' and release_id<>? order by published_at desc,release_sequence desc limit 1";
    private static final String IN_FLIGHT =
            "select count(*) from semantic_query where release_id in (select release_id from semantic_release where project_id=? and runtime_run_id=?) and status in ('PENDING','RUNNING')";
    private static final String MARK =
            "update semantic_release set cleanup_state=? where project_id=? and runtime_run_id=? and cleanup_state<>'CLEANED'";
    private static final String DBT = "DBT",
            PENDING = "PENDING",
            CLEANING = "CLEANING",
            CLEANED = "CLEANED";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DbtRuntimeClient runtime;
    private final GitSyncProperties properties;

    private record Member(UUID id, String state, String cleanupState) {}

    @Scheduled(fixedDelayString = "${metric-platform.dbt-cleanup.poll-ms:60000}")
    public void scheduledCleanup() {
        if (properties.isEnabled()
                && DBT.equals(properties.getMode())
                && properties.getProjectId() != null) cleanupEligible(properties.getProjectId());
    }

    public int cleanupEligible(String projectId) {
        int completed = 0;
        List<UUID> groups = jdbc.query(GROUPS, (rs, row) -> rs.getObject(1, UUID.class), projectId);
        for (UUID runId : groups) {
            Boolean claimed = transactions.execute(status -> claim(projectId, runId));
            if (!Boolean.TRUE.equals(claimed)) continue;
            try {
                runtime.cleanup(runId);
                transactions.executeWithoutResult(
                        status -> jdbc.update(MARK, CLEANED, projectId, runId));
                completed++;
            } catch (DbtRuntimeClient.ServiceFailure failure) {
                transactions.executeWithoutResult(
                        status -> jdbc.update(MARK, PENDING, projectId, runId));
                log.warn(
                        "dbt schema cleanup failed for run {} with status {}",
                        runId,
                        failure.status());
            }
        }
        return completed;
    }

    private boolean claim(String projectId, UUID runId) {
        // 与发布事务先锁项目，再锁版本；查询受理锁版本，清理认领提交后即拒绝新任务。
        UUID active = jdbc.queryForObject(ACTIVE, UUID.class, projectId);
        List<Member> members =
                jdbc.query(
                        LOCK_GROUP,
                        (rs, row) ->
                                new Member(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3)),
                        projectId,
                        runId);
        if (members.isEmpty()
                || members.stream().anyMatch(member -> CLEANED.equals(member.cleanupState())))
            return false;
        UUID previous =
                jdbc.query(
                        PREVIOUS,
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        projectId,
                        active == null ? new UUID(0, 0) : active);
        for (Member member : members) {
            if (member.id().equals(active)
                    || member.id().equals(previous)
                    || !member.state().equals("PUBLISHED")
                            && !member.state().equals("FAILED")
                            && !member.state().equals("SUPERSEDED")) return false;
        }
        int inFlight = jdbc.queryForObject(IN_FLIGHT, Integer.class, projectId, runId);
        if (inFlight > 0) return false;
        jdbc.update(MARK, CLEANING, projectId, runId);
        return true;
    }
}
