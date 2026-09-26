package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 发布候选的 MyBatis Plus 基础映射。 */
@Mapper
public interface SemanticReleaseMapper extends BaseMapper<SemanticReleaseEntity> {
    String LOCK_RELEASE_SQL = "select * from semantic_release where release_id=#{releaseId} for update";
    String LOCK_GROUP_SQL = "select * from semantic_release where project_id=#{projectId} and runtime_run_id=#{runId} order by release_id for update";

    @Select(LOCK_RELEASE_SQL)
    SemanticReleaseEntity lockRelease(@Param("releaseId") UUID releaseId);

    @Select(LOCK_GROUP_SQL)
    List<SemanticReleaseEntity> lockGroup(
            @Param("projectId") String projectId, @Param("runId") UUID runId);
}
