package com.metricplatform.dbt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 常规读写使用 BaseMapper，仅项目观察并发边界保留行锁。 */
@Mapper
public interface SemanticProjectMapper extends BaseMapper<SemanticProjectEntity> {
    String INSERT_BINDING_SQL = "insert into semantic_project(project_id,repository_id,project_subdir,profile_binding_id) values (#{projectId},#{repositoryId},#{projectSubdir},#{profileBindingId}) on conflict (project_id) do nothing";
    String LOCK_PROJECT_SQL = "select * from semantic_project where project_id=#{projectId} for update";

    @Insert(INSERT_BINDING_SQL)
    int insertBinding(SemanticProjectEntity project);

    @Select(LOCK_PROJECT_SQL)
    SemanticProjectEntity lockProject(@Param("projectId") String projectId);
}
