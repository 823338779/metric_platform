package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.SemanticResourceEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SemanticResourceFixtureMapper extends SemanticResourceMapper {
    @Insert(
            """
            insert into semantic_resource(release_id, resource_id, native_id, kind, name, description,
                    source_path, definition_json, queryable)
            values (#{releaseId}, #{resourceId}, #{nativeId}, #{kind}, #{name}, #{description},
                    #{sourcePath}, cast(#{definitionJson} as jsonb), #{queryable})
            on conflict (release_id, resource_id) do update set
                native_id = excluded.native_id, kind = excluded.kind, name = excluded.name,
                description = excluded.description, source_path = excluded.source_path,
                definition_json = excluded.definition_json, queryable = excluded.queryable, is_deleted = false
            where semantic_resource.tenant_id = excluded.tenant_id and semantic_resource.is_deleted
            """)
    int insertOrRestore(SemanticResourceEntity row);
}
