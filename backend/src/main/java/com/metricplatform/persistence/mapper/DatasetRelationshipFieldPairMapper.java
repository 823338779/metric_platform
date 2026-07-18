package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.DatasetRelationshipFieldPairEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DatasetRelationshipFieldPairMapper
        extends AuditableBaseMapper<DatasetRelationshipFieldPairEntity> {
    @Insert(
            """
            INSERT INTO dataset_relationship_field_pair
                (relationship_id, ordinal, source_field_id, target_field_id)
            VALUES (#{relationshipId}, #{ordinal}, #{sourceFieldId}, #{targetFieldId})
            """)
    int insertPair(DatasetRelationshipFieldPairEntity pair);

    @Select(
            """
            SELECT relationship_id, ordinal, source_field_id, target_field_id
            FROM dataset_relationship_field_pair
            WHERE relationship_id = #{relationshipId}
            ORDER BY ordinal
            """)
    List<DatasetRelationshipFieldPairEntity> selectByRelationshipId(UUID relationshipId);

    @Select(
            "SELECT relationship_id, ordinal, source_field_id, target_field_id FROM dataset_relationship_field_pair ORDER BY relationship_id, ordinal")
    List<DatasetRelationshipFieldPairEntity> selectAll();

    @Delete("DELETE FROM dataset_relationship_field_pair WHERE relationship_id = #{relationshipId}")
    int deleteByRelationshipId(UUID relationshipId);
}
