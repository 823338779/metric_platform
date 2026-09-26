package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询普通读写使用 BaseMapper；受理与清理共享 release 行锁。 */
@Mapper
public interface SemanticQueryMapper extends BaseMapper<SemanticQueryEntity> {
    String LOCK_RELEASE_SQL = "select state, cleanup_state from semantic_release where release_id=#{releaseId} and project_id=#{projectId} for update";

    @Select(LOCK_RELEASE_SQL)
    Map<String, Object> lockRelease(@Param("releaseId") UUID releaseId, @Param("projectId") String projectId);
}
