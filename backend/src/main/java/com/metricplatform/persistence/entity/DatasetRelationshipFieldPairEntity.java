package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;

@TableName("dataset_relationship_field_pair")
public class DatasetRelationshipFieldPairEntity {
    private UUID relationshipId;
    private int ordinal;
    private UUID sourceFieldId;
    private UUID targetFieldId;

    public UUID getRelationshipId() {
        return relationshipId;
    }

    public void setRelationshipId(UUID v) {
        relationshipId = v;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int v) {
        ordinal = v;
    }

    public UUID getSourceFieldId() {
        return sourceFieldId;
    }

    public void setSourceFieldId(UUID v) {
        sourceFieldId = v;
    }

    public UUID getTargetFieldId() {
        return targetFieldId;
    }

    public void setTargetFieldId(UUID v) {
        targetFieldId = v;
    }
}
