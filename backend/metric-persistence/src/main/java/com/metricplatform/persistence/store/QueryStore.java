package com.metricplatform.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.persistence.entity.SemanticQueryEntity;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.service.SemanticProjectService;
import com.metricplatform.persistence.service.SemanticQueryService;
import com.metricplatform.query.QueryRepository;
import com.metricplatform.query.ReleaseGoneException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 查询引用与清理认领共用 release 行锁，普通持久化使用 MyBatis Plus。 */
@Repository
@RequiredArgsConstructor
public class QueryStore implements QueryRepository {
    private static final String PUBLISHED = "PUBLISHED", PENDING = "PENDING", RUNNING = "RUNNING";
    private static final String QUERY = "QUERY", COMPLETED = "COMPLETED", FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private final SemanticQueryService mapper;
    private final SemanticProjectService projects;

    public List<PendingQuery> pending(int limit) {
        // 使用分页插件生成 PostgreSQL LIMIT，避免拼接 SQL。
        return mapper
                .page(
                        new Page<>(1, limit, false),
                        new LambdaQueryWrapper<SemanticQueryEntity>()
                                .in(SemanticQueryEntity::getStatus, PENDING, RUNNING)
                                .orderByAsc(SemanticQueryEntity::getSubmittedAt))
                .getRecords()
                .stream()
                .map(row -> new PendingQuery(row.getProjectId(), row.getQueryId()))
                .toList();
    }

    @Transactional
    public void reserve(UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey) {
        reserve(queryId, projectId, releaseId, idempotencyKey, QUERY);
    }

    @Transactional
    public void reserve(
            UUID queryId, String projectId, UUID releaseId, UUID idempotencyKey, String mode) {
        // 与发布、清理共用项目锁，确保受理时版本仍是活动指针。
        SemanticProjectEntity project = projects.lockProject(projectId);
        if (project == null) throw new NoSuchElementException("项目不存在");
        if (!releaseId.equals(project.getActiveReleaseId()))
            throw new ReleaseGoneException("历史发布版本不可查询");
        Map<String, Object> release = mapper.lockRelease(releaseId, projectId);
        if (release == null) throw new NoSuchElementException("发布版本不存在");
        if (!PUBLISHED.equals(release.get("state"))) throw new IllegalStateException("发布版本不可查询");
        if (!PENDING.equals(release.get("cleanup_state")))
            throw new ReleaseGoneException("发布版本已清理");
        SemanticQueryEntity row = new SemanticQueryEntity();
        row.setQueryId(queryId);
        row.setProjectId(projectId);
        row.setReleaseId(releaseId);
        row.setIdempotencyKey(idempotencyKey);
        row.setMode(mode);
        row.setStatus(PENDING);
        mapper.save(row);
    }

    public QueryRecord find(String projectId, UUID queryId) {
        SemanticQueryEntity row =
                mapper.getOne(
                        new LambdaQueryWrapper<SemanticQueryEntity>()
                                .eq(SemanticQueryEntity::getProjectId, projectId)
                                .eq(SemanticQueryEntity::getQueryId, queryId));
        if (row == null) throw new NoSuchElementException("查询任务不存在");
        return new QueryRecord(
                row.getQueryId(),
                row.getReleaseId(),
                row.getIdempotencyKey(),
                row.getRuntimeQueryId(),
                row.getMode(),
                row.getStatus(),
                row.getErrorCode());
    }

    @Transactional
    public void attach(UUID queryId, UUID runtimeQueryId) {
        mapper.update(
                new LambdaUpdateWrapper<SemanticQueryEntity>()
                        .eq(SemanticQueryEntity::getQueryId, queryId)
                        .in(SemanticQueryEntity::getStatus, PENDING, RUNNING)
                        .set(SemanticQueryEntity::getRuntimeQueryId, runtimeQueryId)
                        .set(SemanticQueryEntity::getStatus, RUNNING));
    }

    @Transactional
    public void update(UUID queryId, UUID runtimeQueryId, String status, String errorCode) {
        SemanticQueryEntity current = mapper.getById(queryId);
        if (current == null) throw new NoSuchElementException("查询任务不存在");
        // 远端 ID 首次确认后不可被后续轮询覆盖，终态完成时间由平台写入。
        LambdaUpdateWrapper<SemanticQueryEntity> update =
                new LambdaUpdateWrapper<SemanticQueryEntity>()
                        .eq(SemanticQueryEntity::getQueryId, queryId)
                        .set(SemanticQueryEntity::getStatus, status)
                        .set(SemanticQueryEntity::getErrorCode, errorCode)
                        .set(
                                SemanticQueryEntity::getCompletedAt,
                                List.of(COMPLETED, FAILED, CANCELLED).contains(status)
                                        ? OffsetDateTime.now()
                                        : null);
        if (current.getRuntimeQueryId() == null && runtimeQueryId != null)
            update.set(SemanticQueryEntity::getRuntimeQueryId, runtimeQueryId);
        mapper.update(update);
    }

    public int countNonTerminal(UUID releaseId) {
        return Math.toIntExact(
                mapper.count(
                        new LambdaQueryWrapper<SemanticQueryEntity>()
                                .eq(SemanticQueryEntity::getReleaseId, releaseId)
                                .in(SemanticQueryEntity::getStatus, PENDING, RUNNING)));
    }

    @Transactional
    public void complete(UUID queryId) {
        mapper.update(
                new LambdaUpdateWrapper<SemanticQueryEntity>()
                        .eq(SemanticQueryEntity::getQueryId, queryId)
                        .set(SemanticQueryEntity::getStatus, COMPLETED)
                        .set(SemanticQueryEntity::getCompletedAt, OffsetDateTime.now()));
    }
}
