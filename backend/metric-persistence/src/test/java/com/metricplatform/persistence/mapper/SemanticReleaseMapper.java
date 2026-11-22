package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.SemanticReleaseEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 发布候选的 MyBatis Plus 基础映射。 */
@Mapper
public interface SemanticReleaseMapper extends SemanticReleaseTableMapper {
    String LOCK_RELEASE_SQL =
            "select * from semantic_release where not is_deleted and release_id=#{releaseId} for"
                + " update";

    @Select(LOCK_RELEASE_SQL)
    SemanticReleaseEntity lockRelease(@Param("releaseId") UUID releaseId);
}
