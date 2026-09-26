package com.metricplatform.dbt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 只从已发布版本读取 dbt/MetricFlow 目录，版本校验不回退到当前指针。 */
@Service
@RequiredArgsConstructor
public class DbtCatalogService {
    private static final String PROJECTS =
            "select project_id,observed_sha,active_release_id,last_observation_status from semantic_project order by project_id";
    private static final String PROJECT_ACTIVE =
            "select active_release_id from semantic_project where project_id=?";
    private static final String RELEASE =
            "select release_id,project_id,target_commit_sha,build_source_sha,project_digest,state,cleanup_state,schema_name,error_code,created_at,published_at from semantic_release where project_id=? and release_id=?";
    private static final String RELEASES =
            "select release_id,project_id,target_commit_sha,build_source_sha,project_digest,state,cleanup_state,schema_name,error_code,created_at,published_at from semantic_release where project_id=? order by release_sequence desc limit 100";
    private static final String RESOURCE_COUNT =
            "select count(*) from semantic_resource where release_id=? and lower(name) like ?";
    private static final String RESOURCE_PAGE =
            "select resource_id,native_id,kind,name,description,source_path,definition_json::text,queryable from semantic_resource where release_id=? and lower(name) like ? order by name,resource_id limit ? offset ?";
    private static final String RESOURCE =
            "select resource_id,native_id,kind,name,description,source_path,definition_json::text,queryable from semantic_resource where release_id=? and resource_id=?";
    private static final String LINEAGE =
            "select upstream_resource_id,downstream_resource_id,dependency_kind from semantic_dependency where release_id=? and (upstream_resource_id=? or downstream_resource_id=?) order by upstream_resource_id,downstream_resource_id limit ?";
    private static final String PUBLISHED = "PUBLISHED", PENDING = "PENDING", SEARCH_WILDCARD = "%";
    private static final int MAX_PAGE_SIZE = 100, MAX_LINEAGE = 200;
    private final JdbcTemplate jdbc;

    public record ProjectSummary(
            String projectId,
            String observedSha,
            UUID activeReleaseId,
            String lastObservationStatus) {}

    public record ReleaseSummary(
            UUID releaseId,
            String projectId,
            String targetCommitSha,
            String buildSourceSha,
            String projectDigest,
            String state,
            String cleanupState,
            String schemaName,
            String errorCode,
            OffsetDateTime createdAt,
            OffsetDateTime publishedAt) {}

    public record CatalogPage(
            UUID releaseId,
            int page,
            int size,
            int total,
            List<DbtCatalogStore.Resource> resources) {}

    public record Lineage(
            UUID releaseId, String resourceId, List<DbtCatalogStore.Dependency> dependencies) {}

    public List<ProjectSummary> projects() {
        return jdbc.query(
                PROJECTS,
                (rs, row) ->
                        new ProjectSummary(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getObject(3, UUID.class),
                                rs.getString(4)));
    }

    public List<ReleaseSummary> releases(String projectId) {
        requireProject(projectId);
        return jdbc.query(RELEASES, (rs, row) -> release(rs), projectId);
    }

    public ReleaseSummary release(String projectId, UUID releaseId) {
        return jdbc.query(RELEASE, rs -> rs.next() ? release(rs) : missing(), projectId, releaseId);
    }

    public CatalogPage catalog(
            String projectId, UUID requestedReleaseId, String search, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("目录分页参数超出范围");
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        String pattern =
                SEARCH_WILDCARD + (search == null ? "" : search.toLowerCase()) + SEARCH_WILDCARD;
        int total = jdbc.queryForObject(RESOURCE_COUNT, Integer.class, releaseId, pattern);
        var resources =
                jdbc.query(
                        RESOURCE_PAGE,
                        (rs, row) -> resource(rs),
                        releaseId,
                        pattern,
                        size,
                        Math.multiplyExact(page, size));
        return new CatalogPage(releaseId, page, size, total, resources);
    }

    public DbtCatalogStore.Resource resource(
            String projectId, UUID requestedReleaseId, String resourceId) {
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        return jdbc.query(
                RESOURCE, rs -> rs.next() ? resource(rs) : missing(), releaseId, resourceId);
    }

    public Lineage lineage(
            String projectId, UUID requestedReleaseId, String resourceId, int limit) {
        if (limit < 1 || limit > MAX_LINEAGE) throw new IllegalArgumentException("血缘范围超出限制");
        UUID releaseId = resolvePublished(projectId, requestedReleaseId);
        resource(projectId, releaseId, resourceId);
        var dependencies =
                jdbc.query(
                        LINEAGE,
                        (rs, row) ->
                                new DbtCatalogStore.Dependency(
                                        rs.getString(1), rs.getString(2), rs.getString(3)),
                        releaseId,
                        resourceId,
                        resourceId,
                        limit);
        return new Lineage(releaseId, resourceId, dependencies);
    }

    public UUID resolvePublished(String projectId, UUID requestedReleaseId) {
        UUID releaseId =
                requestedReleaseId == null ? requireProject(projectId) : requestedReleaseId;
        if (releaseId == null) throw new NoSuchElementException("项目尚未发布版本");
        ReleaseSummary release = release(projectId, releaseId);
        if (!PUBLISHED.equals(release.state())) throw new NoSuchElementException("版本尚未发布");
        if (!PENDING.equals(release.cleanupState()))
            throw new ResponseStatusException(HttpStatus.GONE, "发布版本已清理");
        return releaseId;
    }

    private UUID requireProject(String projectId) {
        return jdbc.query(
                PROJECT_ACTIVE,
                rs -> rs.next() ? rs.getObject(1, UUID.class) : missing(),
                projectId);
    }

    private static <T> T missing() {
        throw new NoSuchElementException("项目或发布版本不存在");
    }

    private static ReleaseSummary release(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReleaseSummary(
                rs.getObject("release_id", UUID.class),
                rs.getString("project_id"),
                rs.getString("target_commit_sha"),
                rs.getString("build_source_sha"),
                rs.getString("project_digest"),
                rs.getString("state"),
                rs.getString("cleanup_state"),
                rs.getString("schema_name"),
                rs.getString("error_code"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    private static DbtCatalogStore.Resource resource(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new DbtCatalogStore.Resource(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getBoolean(8));
    }
}
