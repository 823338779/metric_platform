package com.metricplatform.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.persistence.entity.DatasetRelationshipFieldPairEntity;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DatasetRelationshipFieldPairPersistenceService
        extends ServiceImpl<
                DatasetRelationshipFieldPairMapper, DatasetRelationshipFieldPairEntity> {
    public void savePair(DatasetRelationshipFieldPairEntity pair) {
        baseMapper.insertPair(pair);
    }

    public List<DatasetRelationshipFieldPairEntity> listByRelationshipId(UUID relationshipId) {
        return baseMapper.selectByRelationshipId(relationshipId);
    }

    public List<DatasetRelationshipFieldPairEntity> listAllOrdered() {
        return baseMapper.selectAll();
    }

    public void removeByRelationshipId(UUID relationshipId) {
        baseMapper.deleteByRelationshipId(relationshipId);
    }
}
