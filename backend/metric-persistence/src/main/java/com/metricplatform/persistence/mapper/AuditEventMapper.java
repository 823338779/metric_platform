package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.batch.BatchSqlSession;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.metricplatform.persistence.entity.EventEntity;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.executor.BatchResult;

@Mapper
public interface AuditEventMapper extends AuditableBaseMapper<EventEntity> {
    @Override
    default int deleteById(Serializable id) {
        throw immutable();
    }

    @Override
    default int deleteById(Object obj, boolean useFill) {
        throw immutable();
    }

    @Override
    default int deleteById(EventEntity entity) {
        throw immutable();
    }

    @Override
    default int deleteByMap(Map<String, Object> columnMap) {
        throw immutable();
    }

    @Override
    default int delete(Wrapper<EventEntity> queryWrapper) {
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
    default int updateById(EventEntity entity) {
        throw immutable();
    }

    @Override
    default int update(EventEntity entity, Wrapper<EventEntity> updateWrapper) {
        throw immutable();
    }

    @Override
    default int update(Wrapper<EventEntity> updateWrapper) {
        throw immutable();
    }

    @Override
    default boolean insertOrUpdate(EventEntity entity) {
        throw immutable();
    }

    @Override
    default List<BatchResult> updateById(Collection<EventEntity> entityList) {
        throw immutable();
    }

    @Override
    default List<BatchResult> updateById(Collection<EventEntity> entityList, int batchSize) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(Collection<EventEntity> entityList) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<EventEntity> entityList, int batchSize) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<EventEntity> entityList,
            BiPredicate<BatchSqlSession, EventEntity> insertPredicate) {
        throw immutable();
    }

    @Override
    default List<BatchResult> insertOrUpdate(
            Collection<EventEntity> entityList,
            BiPredicate<BatchSqlSession, EventEntity> insertPredicate,
            int batchSize) {
        throw immutable();
    }

    private static UnsupportedOperationException immutable() {
        return new UnsupportedOperationException("audit_event is append-only");
    }
}
