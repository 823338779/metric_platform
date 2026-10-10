package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.*;
import com.metricplatform.persistence.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;

/** 将具体 MyBatis SQL 聚合在持久化服务内。 */
@Service
@RequiredArgsConstructor
public class BuildHistoryPersistenceService
        extends ServiceImpl<BuildHistoryMapper, BuildHistoryEntity> {
    /** 历史消费者的原子水位 Mapper。 */
    private final HistoryCursorMapper cursors;
    /** 部署事实摘要 Mapper。 */
    private final DeploymentHistoryMapper deployments;

    public HistoryCursorEntity cursor() {
        return cursors.read();
    }

    public String lock() {
        return cursors.lock().getCursor();
    }

    public int advance(String next, String expected) {
        return cursors.advance(next, expected);
    }

    public void apply(BuildHistoryEntity row) {
        baseMapper.upsert(row);
    }

    public void apply(DeploymentHistoryEntity row) {
        deployments.upsert(row);
    }

    public List<BuildHistoryEntity> history(
            String repository, String branch, long after, int limit) {
        return baseMapper.list(repository, branch, after, limit);
    }
}
