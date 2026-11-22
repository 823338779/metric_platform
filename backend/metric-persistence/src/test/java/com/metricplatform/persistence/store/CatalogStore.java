package com.metricplatform.persistence.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.catalog.CatalogRepository;
import com.metricplatform.persistence.entity.SemanticDependencyEntity;
import com.metricplatform.persistence.entity.SemanticResourceEntity;
import com.metricplatform.persistence.mapping.CatalogEntityMapper;
import com.metricplatform.persistence.service.SemanticDependencyService;
import com.metricplatform.persistence.service.SemanticResourceService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 使用 MyBatis Plus 保存原生目录投影，调用方在发布事务中替换候选目录。 */
@Repository
@RequiredArgsConstructor
public class CatalogStore implements CatalogRepository {
    private final SemanticResourceService resources;
    private final SemanticDependencyService dependencies;
    private final CatalogEntityMapper entities;

    @Transactional
    public void replace(UUID releaseId, List<Resource> values, List<Dependency> edges) {
        // 先清血缘再清资源，复合外键始终保持有效；外层发布事务失败时整体回滚。
        dependencies.remove(
                new LambdaQueryWrapper<SemanticDependencyEntity>()
                        .eq(SemanticDependencyEntity::getReleaseId, releaseId));
        resources.remove(
                new LambdaQueryWrapper<SemanticResourceEntity>()
                        .eq(SemanticResourceEntity::getReleaseId, releaseId));
        for (Resource value : values)
            resources.insertOrRestore(entities.resource(releaseId, value));
        for (Dependency edge : edges)
            dependencies.insertOrRestore(entities.dependency(releaseId, edge));
    }

    public List<Resource> resources(UUID releaseId) {
        return resources
                .list(
                        new LambdaQueryWrapper<SemanticResourceEntity>()
                                .eq(SemanticResourceEntity::getReleaseId, releaseId)
                                .orderByAsc(SemanticResourceEntity::getResourceId))
                .stream()
                .map(entities::resource)
                .toList();
    }

    public List<Dependency> dependencies(UUID releaseId) {
        return dependencies
                .list(
                        new LambdaQueryWrapper<SemanticDependencyEntity>()
                                .eq(SemanticDependencyEntity::getReleaseId, releaseId)
                                .orderByAsc(SemanticDependencyEntity::getUpstreamResourceId)
                                .orderByAsc(SemanticDependencyEntity::getDownstreamResourceId))
                .stream()
                .map(entities::dependency)
                .toList();
    }
}
