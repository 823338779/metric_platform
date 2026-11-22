package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.SemanticDependencyEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SemanticDependencyFixtureMapper extends SemanticDependencyMapper {
    @Insert(
            """
            insert into semantic_dependency(release_id, upstream_resource_id, downstream_resource_id, dependency_kind)
            values (#{releaseId}, #{upstreamResourceId}, #{downstreamResourceId}, #{kind})
            on conflict (release_id, upstream_resource_id, downstream_resource_id, dependency_kind)
            do update set is_deleted = false
            where semantic_dependency.tenant_id = excluded.tenant_id and semantic_dependency.is_deleted
            """)
    int insertOrRestore(SemanticDependencyEntity row);
}
