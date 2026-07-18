package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.PlatformIdentityDatasetGrantEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformIdentityDatasetGrantMapper
        extends AuditableBaseMapper<PlatformIdentityDatasetGrantEntity> {
    int physicallyDeleteByIdentityId(@Param("identityId") UUID identityId);
}
