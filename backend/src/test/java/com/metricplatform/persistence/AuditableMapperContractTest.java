package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.persistence.mapper.ApiClientMapper;
import com.metricplatform.persistence.mapper.AuditEventMapper;
import com.metricplatform.persistence.mapper.AuditableBaseMapper;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipFieldPairMapper;
import com.metricplatform.persistence.mapper.DatasetRelationshipMapper;
import com.metricplatform.persistence.mapper.DimensionMapper;
import com.metricplatform.persistence.mapper.DimensionTimeGranularityMapper;
import com.metricplatform.persistence.mapper.MetricMapper;
import com.metricplatform.persistence.mapper.PlatformUserMapper;
import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditableMapperContractTest {
    @Test
    void everyMapperUsesTheAuditableBaseContract() throws Exception {
        assertThat(
                        List.of(
                                ApiClientMapper.class,
                                AuditEventMapper.class,
                                DataSourceMapper.class,
                                DatasetFieldMapper.class,
                                DatasetMapper.class,
                                DatasetRelationshipFieldPairMapper.class,
                                DatasetRelationshipMapper.class,
                                DimensionMapper.class,
                                DimensionTimeGranularityMapper.class,
                                MetricMapper.class,
                                PlatformUserMapper.class))
                .allMatch(AuditableBaseMapper.class::isAssignableFrom);
        assertThat(AuditableBaseMapper.class.getMethod("softDeleteById", Serializable.class))
                .isNotNull();
    }
}
