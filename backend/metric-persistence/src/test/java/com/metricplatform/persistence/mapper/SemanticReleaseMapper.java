package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.SemanticReleaseEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 发布候选的 MyBatis Plus 基础映射。 */
@Mapper
public interface SemanticReleaseMapper extends BaseMapper<SemanticReleaseEntity> {
    String LOCK_RELEASE_SQL =
            "select * from semantic_release where release_id=#{releaseId} for update";

    @Select(LOCK_RELEASE_SQL)
    SemanticReleaseEntity lockRelease(@Param("releaseId") UUID releaseId);

}
