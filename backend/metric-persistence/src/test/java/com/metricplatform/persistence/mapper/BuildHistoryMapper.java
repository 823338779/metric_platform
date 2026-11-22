package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.BuildHistoryEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 使用参数绑定更新投影；旧对象版本不能覆盖已知事实。 */
@Mapper
public interface BuildHistoryMapper extends BuildHistoryTableMapper {
    String UPSERT =
            "insert into"
                + " build_history(build_id,repository,branch_name,object_version,first_sequence,last_sequence,summary)"
                + " values(#{buildId},#{repository},#{branchName},#{objectVersion},#{firstSequence},#{lastSequence},#{summary}::jsonb)"
                + " on conflict(tenant_id,build_id) do update set"
                + " repository=excluded.repository,branch_name=excluded.branch_name,object_version=excluded.object_version,last_sequence=excluded.last_sequence,summary=excluded.summary,synced_at=CURRENT_TIMESTAMP"
                + " where build_history.object_version<excluded.object_version";
    String LIST =
            "select tenant_id,build_id,summary::text,first_sequence,synced_at from build_history"
                + " where not is_deleted and (#{repository}::text is null or"
                + " repository=#{repository}) and (#{branch}::text is null or"
                + " branch_name=#{branch}) and first_sequence>#{after} order by first_sequence"
                + " limit #{limit}";

    @Insert(UPSERT)
    int upsert(BuildHistoryEntity row);

    @Select(LIST)
    List<BuildHistoryEntity> list(
            @Param("repository") String repository,
            @Param("branch") String branch,
            @Param("after") long after,
            @Param("limit") int limit);
}
