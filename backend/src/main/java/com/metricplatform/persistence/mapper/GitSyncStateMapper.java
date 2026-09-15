package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.GitSyncStateEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GitSyncStateMapper extends BaseMapper<GitSyncStateEntity> {
    void initialize(
            @Param("repositoryId") String repositoryId,
            @Param("modelName") String modelName,
            @Param("dataSourceId") UUID dataSourceId,
            @Param("schemaName") String schemaName);

    GitSyncStateEntity lock(@Param("repositoryId") String repositoryId);

    String authorize(@Param("repositoryId") String repositoryId);
}
