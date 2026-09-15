package com.metricplatform.gitsync;

import com.metricplatform.persistence.entity.*;
import java.util.List;

/** 已完成外部探测的候选发布内容；所有 ID 在事务前固定，尚未写入任何平台对象。 */
record GitPreparedSnapshot(
        String definitionJson,
        List<GitObjectOwnershipEntity> ownerships,
        List<DatasetEntity> datasets,
        List<DatasetFieldEntity> fields,
        List<DimensionEntity> dimensions,
        List<MetricEntity> metrics,
        List<DatasetRelationshipEntity> relationships,
        List<DatasetRelationshipFieldPairEntity> pairs) {}
