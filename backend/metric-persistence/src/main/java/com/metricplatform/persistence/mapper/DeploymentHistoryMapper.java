package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.DeploymentHistoryEntity;

import org.apache.ibatis.annotations.*;

/** 自然对象身份唯一，版本更新只允许前进。 */
@Mapper
public interface DeploymentHistoryMapper extends BaseMapper<DeploymentHistoryEntity> {
    String UPSERT =
            "insert into"
                + " deployment_history(object_type,object_id,repository,object_version,last_sequence,summary)"
                + " values(#{objectType},#{objectId},#{repository},#{objectVersion},#{lastSequence},#{summary}::jsonb)"
                + " on conflict(tenant_id,object_type,object_id) do update set"
                + " object_version=excluded.object_version,last_sequence=excluded.last_sequence,summary=excluded.summary,synced_at=CURRENT_TIMESTAMP"
                + " where deployment_history.object_version<excluded.object_version";

    @Insert(UPSERT)
    int upsert(DeploymentHistoryEntity row);
}
