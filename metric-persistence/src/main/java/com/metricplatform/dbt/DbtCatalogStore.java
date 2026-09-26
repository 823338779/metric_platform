package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用 MyBatis Plus 保存原生目录投影，调用方在发布事务中替换候选目录。 */
@Repository
@RequiredArgsConstructor
public class DbtCatalogStore implements DbtCatalogRepository {
    private final SemanticResourceMapper resources;
    private final SemanticDependencyMapper dependencies;
    private final DbtEntityMapper entities;

    @Transactional
    public void replace(UUID releaseId, List<Resource> values, List<Dependency> edges) {
        // 先清血缘再清资源，复合外键始终保持有效；外层发布事务失败时整体回滚。
        dependencies.delete(new LambdaQueryWrapper<SemanticDependencyEntity>()
                .eq(SemanticDependencyEntity::getReleaseId, releaseId));
        resources.delete(new LambdaQueryWrapper<SemanticResourceEntity>()
                .eq(SemanticResourceEntity::getReleaseId, releaseId));
        for (Resource value : values) resources.insert(entities.resource(releaseId, value));
        for (Dependency edge : edges) dependencies.insert(entities.dependency(releaseId, edge));
    }

    public List<Resource> resources(UUID releaseId) {
        return resources.selectList(new LambdaQueryWrapper<SemanticResourceEntity>()
                        .eq(SemanticResourceEntity::getReleaseId, releaseId)
                        .orderByAsc(SemanticResourceEntity::getResourceId))
                .stream().map(entities::resource).toList();
    }

    public List<Dependency> dependencies(UUID releaseId) {
        return dependencies.selectList(new LambdaQueryWrapper<SemanticDependencyEntity>()
                        .eq(SemanticDependencyEntity::getReleaseId, releaseId)
                        .orderByAsc(SemanticDependencyEntity::getUpstreamResourceId)
                        .orderByAsc(SemanticDependencyEntity::getDownstreamResourceId))
                .stream().map(entities::dependency).toList();
    }
}
