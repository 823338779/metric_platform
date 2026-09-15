package com.metricplatform.gitsync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.persistence.entity.*;
import com.metricplatform.persistence.service.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台元数据的唯一 Git 发布事务边界；普通 CRUD 复用现有 MP 服务。 */
@Service
@RequiredArgsConstructor
public class GitPublicationService {
    private static final String TIME = "TIME";
    private static final String PENDING_COLUMN_PREFIX = "_git_pending_";
    private static final List<String> GRANULARITIES =
            List.of("DAY", "WEEK", "MONTH", "QUARTER", "YEAR");
    private final GitSyncStatePersistenceService states;
    private final GitObjectOwnershipPersistenceService ownerships;
    private final DatasetEntityPersistenceService datasets;
    private final DatasetFieldPersistenceService fields;
    private final DimensionEntityPersistenceService dimensions;
    private final DimensionTimeGranularityPersistenceService granularities;
    private final MetricEntityPersistenceService metrics;
    private final DatasetRelationshipEntityPersistenceService relationships;
    private final DatasetRelationshipFieldPairPersistenceService pairs;

    public GitImportPort.State state(GitSyncProperties properties) {
        var state = states.getById(properties.getRepositoryId());
        return state == null
                ? new GitImportPort.State(
                        properties.getRepositoryId(),
                        null,
                        properties.getModelName(),
                        null,
                        null,
                        null)
                : new GitImportPort.State(
                        state.getRepositoryId(),
                        state.getAppliedCommit(),
                        state.getModelName(),
                        state.getErrorCode(),
                        state.getErrorMessage(),
                        state.getUpdatedAt());
    }

    @Transactional
    public boolean publish(
            GitSyncProperties properties,
            String expectedCommit,
            String commit,
            GitPreparedSnapshot prepared) {
        // 锁内重新比较读快照前的检查点，慢任务只能放弃，不能把较新的已发布结果覆盖回去。
        var state =
                states.initializeAndLock(
                        properties.getRepositoryId(),
                        properties.getModelName(),
                        properties.getDataSourceId(),
                        properties.getSchema());
        if (!Objects.equals(state.getAppliedCommit(), expectedCommit)) return false;
        requireBinding(state, properties);
        if (Objects.equals(state.getAppliedCommit(), commit)) {
            clearError(state.getRepositoryId());
            return false;
        }
        states.authorizePublication(properties.getRepositoryId());
        var previous =
                ownerships.list(
                        new LambdaQueryWrapper<GitObjectOwnershipEntity>()
                                .eq(
                                        GitObjectOwnershipEntity::getRepositoryId,
                                        properties.getRepositoryId()));
        var retained =
                prepared.ownerships().stream()
                        .map(GitObjectOwnershipEntity::getObjectId)
                        .collect(Collectors.toSet());
        var removed =
                previous.stream().filter(value -> !retained.contains(value.getObjectId())).toList();

        // 外部依赖阻止整个删除快照；归属墓碑永远保留。先软删除再恢复可释放活动名称约束。
        for (var ownership : removed) {
            if (ownerships.hasExternalReferences(
                    ownership.getObjectId(), properties.getRepositoryId()))
                throw new ConflictException("Git deletion has external dependencies");
        }
        for (var ownership : removed) remove(ownership);
        for (var ownership : prepared.ownerships()) {
            var previousOwner = ownerships.getById(ownership.getObjectId());
            if (previousOwner == null) {
                if (exists(GitObjectKind.fromTable(ownership.getKind()), ownership.getObjectId()))
                    throw new ConflictException(
                            "Git object identity collides with a manual object");
                ownerships.save(ownership);
            } else if (!previousOwner.getRepositoryId().equals(properties.getRepositoryId())
                    || !previousOwner.getModelName().equals(properties.getModelName())
                    || !previousOwner.getLogicalName().equals(ownership.getLogicalName())
                    || !previousOwner.getKind().equals(ownership.getKind())) {
                throw new ConflictException("Git object is owned by another logical definition");
            }
        }
        // 列映射可以互换；先释放所有变更列的活动唯一键，避免逐行更新的临时冲突。
        // 占位值只存在于本事务，MVCC 读者始终看到旧或新快照，不能看到占位列。
        for (var field : prepared.fields()) {
            var previousField = fields.getById(field.getId());
            if (previousField != null
                    && !previousField.getPhysicalName().equals(field.getPhysicalName())) {
                requireUpdated(
                        fields.update(
                                new LambdaUpdateWrapper<DatasetFieldEntity>()
                                        .eq(DatasetFieldEntity::getId, field.getId())
                                        .set(
                                                DatasetFieldEntity::getPhysicalName,
                                                PENDING_COLUMN_PREFIX + field.getId())));
            }
        }
        for (var ownership : prepared.ownerships()) {
            ownerships.restore(
                    GitObjectKind.fromTable(ownership.getKind()), ownership.getObjectId());
        }

        // 写入顺序服从外键依赖。可空字段必须显式清空，否则 MP 默认忽略 null 会留下旧口径。
        prepared.datasets().forEach(this::saveDataset);
        prepared.fields().forEach(this::saveField);
        prepared.dimensions().forEach(this::saveDimension);
        prepared.metrics().forEach(this::saveMetric);
        prepared.relationships().forEach(this::saveRelationship);
        prepared.pairs().forEach(pairs::savePair);

        // 完整定义和 appliedCommit 与所有业务对象一起提交，任何后续写失败都会整体回滚。
        states.update(
                new LambdaUpdateWrapper<GitSyncStateEntity>()
                        .eq(GitSyncStateEntity::getRepositoryId, properties.getRepositoryId())
                        .set(GitSyncStateEntity::getAppliedCommit, commit)
                        .set(GitSyncStateEntity::getDefinitionJson, prepared.definitionJson())
                        .set(GitSyncStateEntity::getErrorCode, null)
                        .set(GitSyncStateEntity::getErrorMessage, null)
                        .set(GitSyncStateEntity::getUpdatedAt, Instant.now()));
        return true;
    }

    @Transactional
    public void failed(
            GitSyncProperties properties, String expectedCommit, String code, String message) {
        var state =
                states.initializeAndLock(
                        properties.getRepositoryId(),
                        properties.getModelName(),
                        properties.getDataSourceId(),
                        properties.getSchema());
        if (!Objects.equals(state.getAppliedCommit(), expectedCommit)) return;
        states.update(
                new LambdaUpdateWrapper<GitSyncStateEntity>()
                        .eq(GitSyncStateEntity::getRepositoryId, properties.getRepositoryId())
                        .set(GitSyncStateEntity::getErrorCode, code)
                        .set(GitSyncStateEntity::getErrorMessage, message)
                        .set(GitSyncStateEntity::getUpdatedAt, Instant.now()));
    }

    @Transactional
    public void healthy(GitSyncProperties properties, String expectedCommit) {
        var state =
                states.initializeAndLock(
                        properties.getRepositoryId(),
                        properties.getModelName(),
                        properties.getDataSourceId(),
                        properties.getSchema());
        requireBinding(state, properties);
        if (Objects.equals(state.getAppliedCommit(), expectedCommit))
            clearError(properties.getRepositoryId());
    }

    private void clearError(String repositoryId) {
        states.update(
                new LambdaUpdateWrapper<GitSyncStateEntity>()
                        .eq(GitSyncStateEntity::getRepositoryId, repositoryId)
                        .set(GitSyncStateEntity::getErrorCode, null)
                        .set(GitSyncStateEntity::getErrorMessage, null)
                        .set(GitSyncStateEntity::getUpdatedAt, Instant.now()));
    }

    private static void requireBinding(GitSyncStateEntity state, GitSyncProperties properties) {
        // 相同 SHA 也必须检查绑定；否则配置改动会在不重新导入时冒充同步成功。
        if (!Objects.equals(state.getModelName(), properties.getModelName())
                || !Objects.equals(state.getDataSourceId(), properties.getDataSourceId())
                || !Objects.equals(state.getSchemaName(), properties.getSchema())) {
            throw new ConflictException("Git repository binding is immutable");
        }
    }

    private boolean exists(GitObjectKind kind, UUID id) {
        return switch (kind) {
            case DATASET -> datasets.getById(id) != null;
            case FIELD -> fields.getById(id) != null;
            case DIMENSION -> dimensions.getById(id) != null;
            case METRIC -> metrics.getById(id) != null;
            case RELATIONSHIP -> relationships.getById(id) != null;
        };
    }

    private void remove(GitObjectOwnershipEntity ownership) {
        UUID id = ownership.getObjectId();
        switch (GitObjectKind.fromTable(ownership.getKind())) {
            case DATASET -> datasets.softDeleteById(id);
            case FIELD -> fields.softDeleteById(id);
            case DIMENSION -> {
                granularities.removeByDimensionId(id);
                dimensions.softDeleteById(id);
            }
            case METRIC -> metrics.softDeleteById(id);
            case RELATIONSHIP -> {
                pairs.removeByRelationshipId(id);
                relationships.softDeleteById(id);
            }
        }
    }

    private void saveDataset(DatasetEntity entity) {
        var previous = datasets.getById(entity.getId());
        if (previous == null) datasets.save(entity);
        else {
            entity.setVersion(previous.getVersion());
            requireUpdated(
                    datasets.update(
                            entity,
                            new LambdaUpdateWrapper<DatasetEntity>()
                                    .eq(DatasetEntity::getId, entity.getId())
                                    .set(
                                            entity.getDefaultTimeDimensionId() == null,
                                            DatasetEntity::getDefaultTimeDimensionId,
                                            null)
                                    .set(DatasetEntity::getFilterExpression, null)));
        }
    }

    private void saveField(DatasetFieldEntity entity) {
        var previous = fields.getById(entity.getId());
        if (previous == null) fields.save(entity);
        else {
            entity.setVersion(previous.getVersion());
            requireUpdated(fields.updateById(entity));
        }
    }

    private void saveDimension(DimensionEntity entity) {
        var previous = dimensions.getById(entity.getId());
        if (previous == null) dimensions.save(entity);
        else {
            entity.setVersion(previous.getVersion());
            requireUpdated(
                    dimensions.update(
                            entity,
                            new LambdaUpdateWrapper<DimensionEntity>()
                                    .eq(DimensionEntity::getId, entity.getId())
                                    .set(
                                            entity.getDefaultGranularity() == null,
                                            DimensionEntity::getDefaultGranularity,
                                            null)));
        }
        granularities.removeByDimensionId(entity.getId());
        if (TIME.equals(entity.getDimensionType())) {
            for (String value : GRANULARITIES) {
                var granularity = new DimensionTimeGranularityEntity();
                granularity.setDimensionId(entity.getId());
                granularity.setGranularity(value);
                granularities.saveGranularity(granularity);
            }
        }
    }

    private void saveMetric(MetricEntity entity) {
        var previous = metrics.getById(entity.getId());
        if (previous == null) metrics.save(entity);
        else {
            entity.setVersion(previous.getVersion());
            requireUpdated(
                    metrics.update(
                            entity,
                            new LambdaUpdateWrapper<MetricEntity>()
                                    .eq(MetricEntity::getId, entity.getId())
                                    .set(
                                            entity.getDatasetFieldId() == null,
                                            MetricEntity::getDatasetFieldId,
                                            null)
                                    .set(
                                            entity.getDefaultTimeDimensionId() == null,
                                            MetricEntity::getDefaultTimeDimensionId,
                                            null)
                                    .set(
                                            entity.getFixedFilterExpression() == null,
                                            MetricEntity::getFixedFilterExpression,
                                            null)));
        }
    }

    private void saveRelationship(DatasetRelationshipEntity entity) {
        var previous = relationships.getById(entity.getId());
        if (previous == null) relationships.save(entity);
        else {
            entity.setVersion(previous.getVersion());
            requireUpdated(relationships.updateById(entity));
        }
        pairs.removeByRelationshipId(entity.getId());
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) throw new ConflictException("Git object changed during publication");
    }
}
