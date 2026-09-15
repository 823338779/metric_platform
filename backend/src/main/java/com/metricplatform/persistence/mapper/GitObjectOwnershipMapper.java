package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.GitObjectOwnershipEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GitObjectOwnershipMapper extends BaseMapper<GitObjectOwnershipEntity> {
    void restore(@Param("table") String table, @Param("id") UUID id);

    int externalReferences(@Param("id") UUID id, @Param("repositoryId") String repositoryId);
}
