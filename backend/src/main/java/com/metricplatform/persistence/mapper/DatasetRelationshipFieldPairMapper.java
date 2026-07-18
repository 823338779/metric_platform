package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DatasetRelationshipFieldPairEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DatasetRelationshipFieldPairMapper
        extends AuditableBaseMapper<DatasetRelationshipFieldPairEntity> {
    int insertPair(DatasetRelationshipFieldPairEntity pair);

    List<DatasetRelationshipFieldPairEntity> selectByRelationshipId(
            @Param("relationshipId") UUID relationshipId);

    List<DatasetRelationshipFieldPairEntity> selectAll();

    int deleteByRelationshipId(@Param("relationshipId") UUID relationshipId);
}
