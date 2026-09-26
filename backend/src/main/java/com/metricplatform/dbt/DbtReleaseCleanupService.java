package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.gitsync.GitSyncProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private static final String PUBLISHED = "PUBLISHED", FAILED = "FAILED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String PENDING = "PENDING", CLEANING = "CLEANING", CLEANED = "CLEANED";
    private static final String RUNNING = "RUNNING";
    private final SemanticProjectMapper projects;
    private final SemanticReleaseMapper releases;
    private final SemanticQueryMapper queries;
    private final TransactionTemplate transactions;
    private final DbtRuntimeClient runtime;
    private final GitSyncProperties properties;

    @Scheduled(fixedDelayString = "${metric-platform.dbt-cleanup.poll-ms:60000}")
    public void scheduledCleanup() {
        if (properties.isEnabled() && properties.getProjectId() != null)
            cleanupEligible(properties.getProjectId());
    }

    public int cleanupEligible(String projectId) {
        int completed = 0;
        List<UUID> groups =
                releases
                        .selectList(
                                new LambdaQueryWrapper<SemanticReleaseEntity>()
                                        .eq(SemanticReleaseEntity::getProjectId, projectId)
                                        .isNotNull(SemanticReleaseEntity::getRuntimeRunId)
                                        .ne(SemanticReleaseEntity::getCleanupState, CLEANED))
                        .stream()
                        .map(SemanticReleaseEntity::getRuntimeRunId)
                        .distinct()
                        .toList();
        for (UUID runId : groups) {
            Boolean claimed = transactions.execute(status -> claim(projectId, runId));
            if (!Boolean.TRUE.equals(claimed)) continue;
            try {
                runtime.cleanup(runId);
                transactions.executeWithoutResult(status -> mark(projectId, runId, CLEANED));
                completed++;
            } catch (DbtRuntimeClient.ServiceFailure failure) {
                transactions.executeWithoutResult(status -> mark(projectId, runId, PENDING));
                log.warn(
                        "dbt schema cleanup failed for run {} with status {}",
                        runId,
                        failure.status());
            }
        }
        return completed;
    }

    private boolean claim(String projectId, UUID runId) {
        // 锁顺序与发布事务一致：先项目，后同一物理 run 的全部 release。
        SemanticProjectEntity project = projects.lockProject(projectId);
        if (project == null) return false;
        List<SemanticReleaseEntity> members = releases.lockGroup(projectId, runId);
        if (members.isEmpty()
                || members.stream().anyMatch(member -> CLEANED.equals(member.getCleanupState())))
            return false;
        OffsetDateTime recoveryBefore = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        if (members.stream()
                .anyMatch(
                        member ->
                                CLEANING.equals(member.getCleanupState())
                                        && (member.getUpdatedAt() == null
                                                || member.getUpdatedAt().isAfter(recoveryBefore))))
            return false;
        UUID active = project.getActiveReleaseId();
        LambdaQueryWrapper<SemanticReleaseEntity> previousQuery =
                new LambdaQueryWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getProjectId, projectId)
                        .eq(SemanticReleaseEntity::getState, PUBLISHED)
                        .orderByDesc(SemanticReleaseEntity::getPublishedAt)
                        .orderByDesc(SemanticReleaseEntity::getReleaseSequence);
        if (active != null) previousQuery.ne(SemanticReleaseEntity::getReleaseId, active);
        List<SemanticReleaseEntity> previousPage =
                releases.selectPage(new Page<>(1, 1, false), previousQuery).getRecords();
        UUID previous = previousPage.isEmpty() ? null : previousPage.getFirst().getReleaseId();
        for (SemanticReleaseEntity member : members) {
            if (member.getReleaseId().equals(active)
                    || member.getReleaseId().equals(previous)
                    || !List.of(PUBLISHED, FAILED, SUPERSEDED).contains(member.getState()))
                return false;
        }
        List<UUID> ids = members.stream().map(SemanticReleaseEntity::getReleaseId).toList();
        if (queries.selectCount(
                        new LambdaQueryWrapper<SemanticQueryEntity>()
                                .in(SemanticQueryEntity::getReleaseId, ids)
                                .in(SemanticQueryEntity::getStatus, PENDING, RUNNING))
                > 0) return false;
        mark(projectId, runId, CLEANING);
        return true;
    }

    private void mark(String projectId, UUID runId, String state) {
        releases.update(
                new LambdaUpdateWrapper<SemanticReleaseEntity>()
                        .eq(SemanticReleaseEntity::getProjectId, projectId)
                        .eq(SemanticReleaseEntity::getRuntimeRunId, runId)
                        .ne(SemanticReleaseEntity::getCleanupState, CLEANED)
                        .set(SemanticReleaseEntity::getCleanupState, state)
                        .set(
                                SemanticReleaseEntity::getUpdatedAt,
                                OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
