package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("dataset_relationship_field_pair")
public class DatasetRelationshipFieldPairEntity extends BaseAuditEntity {
    private UUID relationshipId;
    private int ordinal;
    private UUID sourceFieldId;
    private UUID targetFieldId;
}
