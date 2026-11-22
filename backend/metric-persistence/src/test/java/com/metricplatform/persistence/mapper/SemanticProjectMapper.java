package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.SemanticProjectEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅保留项目绑定的冲突处理与发布并发边界所需的 SQL。 */
@Mapper
public interface SemanticProjectMapper extends SemanticProjectTableMapper {
    String INSERT_BINDING_SQL =
            "insert into"
                + " semantic_project(project_id,repository_id,project_subdir,profile_binding_id)"
                + " values (#{projectId},#{repositoryId},#{projectSubdir},#{profileBindingId}) on"
                + " conflict (tenant_id,project_id) do nothing";
    String LOCK_PROJECT_SQL =
            "select * from semantic_project where not is_deleted and project_id=#{projectId} for"
                + " update";

    @Insert(INSERT_BINDING_SQL)
    int insertBinding(SemanticProjectEntity project);

    @Select(LOCK_PROJECT_SQL)
    SemanticProjectEntity lockProject(@Param("projectId") String projectId);
}
