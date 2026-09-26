package com.metricplatform.dbt;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 保存外部服务返回的不可编辑目录投影；调用方在发布事务中替换候选目录。 */
@Repository
@RequiredArgsConstructor
public class DbtCatalogStore {
    private static final String DELETE_DEPENDENCIES =
            "delete from semantic_dependency where release_id=?";
    private static final String DELETE_RESOURCES =
            "delete from semantic_resource where release_id=?";
    private static final String INSERT_RESOURCE =
            "insert into semantic_resource(release_id,resource_id,native_id,kind,name,description,source_path,definition_json,queryable) values (?,?,?,?,?,?,?,?::jsonb,?)";
    private static final String INSERT_DEPENDENCY =
            "insert into semantic_dependency(release_id,upstream_resource_id,downstream_resource_id,dependency_kind) values (?,?,?,?)";
    private static final String SELECT_RESOURCES =
            "select resource_id,native_id,kind,name,description,source_path,definition_json::text,queryable from semantic_resource where release_id=? order by resource_id";
    private static final String SELECT_DEPENDENCIES =
            "select upstream_resource_id,downstream_resource_id,dependency_kind from semantic_dependency where release_id=? order by upstream_resource_id,downstream_resource_id";
    private final JdbcTemplate jdbc;

    public record Resource(
            String resourceId,
            String nativeId,
            String kind,
            String name,
            String description,
            String sourcePath,
            String definitionJson,
            boolean queryable) {}

    public record Dependency(String upstreamResourceId, String downstreamResourceId, String kind) {}

    @Transactional
    public void replace(UUID releaseId, List<Resource> resources, List<Dependency> dependencies) {
        jdbc.update(DELETE_DEPENDENCIES, releaseId);
        jdbc.update(DELETE_RESOURCES, releaseId);
        for (Resource resource : resources)
            jdbc.update(
                    INSERT_RESOURCE,
                    releaseId,
                    resource.resourceId(),
                    resource.nativeId(),
                    resource.kind(),
                    resource.name(),
                    resource.description(),
                    resource.sourcePath(),
                    resource.definitionJson(),
                    resource.queryable());
        for (Dependency dependency : dependencies)
            jdbc.update(
                    INSERT_DEPENDENCY,
                    releaseId,
                    dependency.upstreamResourceId(),
                    dependency.downstreamResourceId(),
                    dependency.kind());
    }

    public List<Resource> resources(UUID releaseId) {
        return jdbc.query(
                SELECT_RESOURCES,
                (rs, row) ->
                        new Resource(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getBoolean(8)),
                releaseId);
    }

    public List<Dependency> dependencies(UUID releaseId) {
        return jdbc.query(
                SELECT_DEPENDENCIES,
                (rs, row) -> new Dependency(rs.getString(1), rs.getString(2), rs.getString(3)),
                releaseId);
    }
}
