package com.metricplatform.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.entity.SemanticQueryEntity;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import com.metricplatform.persistence.service.SemanticProjectService;
import com.metricplatform.persistence.service.SemanticQueryService;
import com.metricplatform.persistence.service.SemanticReleaseService;
import com.metricplatform.publication.CleanupRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 按物理 runId 认领清理；查询受理与清理共享 release 行锁。 */
@Repository
@RequiredArgsConstructor
public class CleanupStore implements CleanupRepository {
    private static final String PUBLISHED = "PUBLISHED", FAILED = "FAILED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String PENDING = "PENDING", CLEANING = "CLEANING", CLEANED = "CLEANED";
    private static final String RUNNING = "RUNNING";
    private final SemanticProjectService projects;
    private final SemanticReleaseService releases;
    private final SemanticQueryService queries;

    public List<UUID> candidateRunIds(String projectId) {
        return releases
                .list(
                        new LambdaQueryWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .isNotNull(SemanticReleaseEntity::getRuntimeRunId)
                                .ne(SemanticReleaseEntity::getCleanupState, CLEANED))
                .stream()
                .map(SemanticReleaseEntity::getRuntimeRunId)
                .distinct()
                .toList();
    }

    @Transactional
    public boolean claim(String projectId, UUID runId) {
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
                releases.page(new Page<>(1, 1, false), previousQuery).getRecords();
        UUID previous = previousPage.isEmpty() ? null : previousPage.getFirst().getReleaseId();
        for (SemanticReleaseEntity member : members) {
            if (member.getReleaseId().equals(active)
                    || member.getReleaseId().equals(previous)
                    || !List.of(PUBLISHED, FAILED, SUPERSEDED).contains(member.getState()))
                return false;
        }
        List<UUID> ids = members.stream().map(SemanticReleaseEntity::getReleaseId).toList();
        if (queries.count(
                        new LambdaQueryWrapper<SemanticQueryEntity>()
                                .in(SemanticQueryEntity::getReleaseId, ids)
                                .in(SemanticQueryEntity::getStatus, PENDING, RUNNING))
                > 0) return false;
        mark(projectId, runId, CLEANING);
        return true;
    }

    @Transactional
    public void markCleaned(String projectId, UUID runId) {
        mark(projectId, runId, CLEANED);
    }

    @Transactional
    public void markPending(String projectId, UUID runId) {
        mark(projectId, runId, PENDING);
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
