package com.metricplatform.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;
import java.util.UUID;

/** 查询受理与清理共享 release 行锁。 */
@Mapper
public interface SemanticQueryMapper extends SemanticQueryTableMapper {
    String LOCK_RELEASE_SQL =
            "select state, cleanup_state from semantic_release where not is_deleted and"
                + " release_id=#{releaseId} and project_id=#{projectId} for update";

    @Select(LOCK_RELEASE_SQL)
    Map<String, Object> lockRelease(
            @Param("releaseId") UUID releaseId, @Param("projectId") String projectId);
}
