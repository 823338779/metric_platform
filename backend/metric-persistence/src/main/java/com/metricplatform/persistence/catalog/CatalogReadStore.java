package com.metricplatform.persistence.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.catalog.CatalogReadRepository;
import com.metricplatform.catalog.CatalogRepository;
import com.metricplatform.persistence.entity.SemanticDependencyEntity;
import com.metricplatform.persistence.entity.SemanticProjectEntity;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import com.metricplatform.persistence.entity.SemanticResourceEntity;
import com.metricplatform.persistence.mapping.CatalogEntityMapper;
import com.metricplatform.persistence.service.SemanticDependencyService;
import com.metricplatform.persistence.service.SemanticProjectService;
import com.metricplatform.persistence.service.SemanticReleaseService;
import com.metricplatform.persistence.service.SemanticResourceService;
import com.metricplatform.query.ReleaseGoneException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 只从已发布版本读取语义目录，清理后的版本不回退当前指针。 */
@Service
@RequiredArgsConstructor
public class CatalogReadStore implements CatalogReadRepository {
    private static final String PUBLISHED = "PUBLISHED", PENDING = "PENDING";
    private static final String NAME_SEARCH = "lower(name) like {0}";
    private static final int MAX_PAGE_SIZE = 100, MAX_LINEAGE = 200;
    private final SemanticProjectService projects;
    private final SemanticReleaseService releases;
    private final SemanticResourceService resources;
    private final SemanticDependencyService dependencies;
    private final CatalogEntityMapper entities;

    public List<ProjectSummary> projects() {
        return projects
                .list(
                        new LambdaQueryWrapper<SemanticProjectEntity>()
                                .orderByAsc(SemanticProjectEntity::getProjectId))
                .stream()
                .map(
                        row ->
                                new ProjectSummary(
                                        row.getProjectId(),
                                        row.getObservedSha(),
                                        row.getActiveReleaseId(),
                                        row.getLastObservationStatus()))
                .toList();
    }

    public List<ReleaseSummary> releases(String projectId) {
        requireProject(projectId);
        return releases
                .page(
                        new Page<>(1, 100, false),
                        new LambdaQueryWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .orderByDesc(SemanticReleaseEntity::getReleaseSequence))
                .getRecords()
                .stream()
                .map(CatalogReadStore::summary)
                .toList();
    }

    public ReleaseSummary release(String projectId, UUID releaseId) {
        SemanticReleaseEntity row =
                releases.getOne(
                        new LambdaQueryWrapper<SemanticReleaseEntity>()
                                .eq(SemanticReleaseEntity::getProjectId, projectId)
                                .eq(SemanticReleaseEntity::getReleaseId, releaseId));
        if (row == null) throw missing();
        return summary(row);
    }

    public CatalogPage catalog(
            String projectId,
            UUID requestedReleaseId,
            String search,
            String kind,
            int page,
            int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("目录分页参数超出范围");
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        String pattern = "%" + (search == null ? "" : search.toLowerCase(Locale.ROOT)) + "%";
        LambdaQueryWrapper<SemanticResourceEntity> filter =
                resourceFilter(releaseId, pattern, kind);
        int total = Math.toIntExact(resources.count(filter));
        var rows =
                resources
                        .page(
                                new Page<>(page + 1L, size, false),
                                resourceFilter(releaseId, pattern, kind)
                                        .orderByAsc(SemanticResourceEntity::getName)
                                        .orderByAsc(SemanticResourceEntity::getResourceId))
                        .getRecords();
        return new CatalogPage(
                releaseId, page, size, total, rows.stream().map(entities::resource).toList());
    }

    /** 类型条件在数据库分页前应用，保证筛选后的总数与页内容一致。 */
    private static LambdaQueryWrapper<SemanticResourceEntity> resourceFilter(
            UUID releaseId, String pattern, String kind) {
        return new LambdaQueryWrapper<SemanticResourceEntity>()
                .eq(SemanticResourceEntity::getReleaseId, releaseId)
                .eq(kind != null && !kind.isBlank(), SemanticResourceEntity::getKind, kind)
                .apply(NAME_SEARCH, pattern);
    }

    public CatalogRepository.Resource resource(
            String projectId, UUID requestedReleaseId, String resourceId) {
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        SemanticResourceEntity row =
                resources.getOne(
                        new LambdaQueryWrapper<SemanticResourceEntity>()
                                .eq(SemanticResourceEntity::getReleaseId, releaseId)
                                .eq(SemanticResourceEntity::getResourceId, resourceId));
        if (row == null) throw missing();
        return entities.resource(row);
    }

    public Lineage lineage(
            String projectId, UUID requestedReleaseId, String resourceId, int limit) {
        if (limit < 1 || limit > MAX_LINEAGE) throw new IllegalArgumentException("血缘范围超出限制");
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        resource(projectId, releaseId, resourceId);
        var edges =
                dependencies
                        .page(
                                new Page<>(1, limit, false),
                                new LambdaQueryWrapper<SemanticDependencyEntity>()
                                        .eq(SemanticDependencyEntity::getReleaseId, releaseId)
                                        .and(
                                                query ->
                                                        query.eq(
                                                                        SemanticDependencyEntity
                                                                                ::getUpstreamResourceId,
                                                                        resourceId)
                                                                .or()
                                                                .eq(
                                                                        SemanticDependencyEntity
                                                                                ::getDownstreamResourceId,
                                                                        resourceId))
                                        .orderByAsc(SemanticDependencyEntity::getUpstreamResourceId)
                                        .orderByAsc(
                                                SemanticDependencyEntity::getDownstreamResourceId))
                        .getRecords();
        return new Lineage(
                releaseId, resourceId, edges.stream().map(entities::dependency).toList());
    }

    public UUID resolvePublished(String projectId, UUID requestedReleaseId) {
        UUID activeReleaseId = requireProject(projectId);
        UUID releaseId = requestedReleaseId == null ? activeReleaseId : requestedReleaseId;
        if (releaseId == null) throw new NoSuchElementException("项目尚未发布版本");
        ReleaseSummary summary = release(projectId, releaseId);
        if (!PUBLISHED.equals(summary.state())) throw new NoSuchElementException("版本尚未发布");
        if (!releaseId.equals(activeReleaseId))
            throw new ReleaseGoneException("历史发布版本不可查询");
        if (!PENDING.equals(summary.cleanupState()))
            throw new ReleaseGoneException("发布版本已清理");
        return releaseId;
    }

    private UUID requireProject(String projectId) {
        SemanticProjectEntity project = projects.getById(projectId);
        if (project == null) throw missing();
        return project.getActiveReleaseId();
    }

    private static NoSuchElementException missing() {
        return new NoSuchElementException("项目或发布版本不存在");
    }

    private static ReleaseSummary summary(SemanticReleaseEntity row) {
        return new ReleaseSummary(
                row.getReleaseId(),
                row.getProjectId(),
                row.getTargetCommitSha(),
                row.getBuildSourceSha(),
                row.getProjectDigest(),
                row.getState(),
                row.getCleanupState(),
                row.getSchemaName(),
                row.getErrorCode(),
                row.getCreatedAt(),
                row.getPublishedAt());
    }
}
