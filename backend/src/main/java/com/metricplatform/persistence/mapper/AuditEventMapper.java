package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.batch.BatchSqlSession;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.AuditEventEntity;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.executor.BatchResult;

@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {
    @Override
    default int deleteById(Serializable id) {
        throw immutable();
    }

    @Override
    default int deleteById(Object obj, boolean useFill) {
        throw immutable();
    }

    @Override
    default int deleteById(AuditEventEntity entity) {
        throw immutable();
    }

    @Override
    default int deleteByMap(Map<String, Object> columnMap) {
        throw immutable();
    }

    @Override
    default int delete(Wrapper<AuditEventEntity> queryWrapper) {
        throw immutable();
    }

    @Override
    default int deleteBatchIds(Collection<?> idList) {
        throw immutable();
    }

    @Override
    default int deleteByIds(Collection<?> idList) {
        throw immutable();
    }

    @Override
    default int deleteByIds(Collection<?> idList, boolean useFill) {
        throw immutable();
    }

    @Override
    default int updateById(AuditEventEntity entity) {
        throw immutable();
    }

    @Override
    default int update(AuditEventEntity entity, Wrapper<AuditEventEntity> updateWrapper) {
        throw immutable();
    }

    @Override
    default int update(Wrapper<AuditEventEntity> updateWrapper) {
        throw immutable();
    }

    @Override
    default boolean insertOrUpdate(AuditEventEntity entity) {
        throw immutable();
    }

    @Override
    default List<BatchResult> updateById(Collection<AuditEventEntity> entityList) {
        throw immutable();
    }

    @Override
    default List<BatchResult> updateById(Collection<AuditEventEntity> entityList, int batchSize) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(Collection<AuditEventEntity> entityList) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<AuditEventEntity> entityList, int batchSize) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<AuditEventEntity> entityList,
            BiPredicate<BatchSqlSession, AuditEventEntity> insertPredicate) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<AuditEventEntity> entityList,
            BiPredicate<BatchSqlSession, AuditEventEntity> insertPredicate,
            int batchSize) {
        throw immutable();
    }

    private static UnsupportedOperationException immutable() {
        return new UnsupportedOperationException("audit_event is append-only");
    }
}
