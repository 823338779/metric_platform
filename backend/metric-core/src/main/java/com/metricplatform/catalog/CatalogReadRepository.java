package com.metricplatform.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 已发布目录的只读端口，应用层不感知数据库实体。 */
public interface CatalogReadRepository {
    record ProjectSummary(
            String projectId,
            String observedSha,
            UUID activeReleaseId,
            String lastObservationStatus) {}

    record ReleaseSummary(
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

    record CatalogPage(
            UUID releaseId,
            int page,
            int size,
            int total,
            List<CatalogRepository.Resource> resources) {}

    record Lineage(
            UUID releaseId,
            String resourceId,
            List<CatalogRepository.Dependency> dependencies) {}

    List<ProjectSummary> projects();

    List<ReleaseSummary> releases(String projectId);

    ReleaseSummary release(String projectId, UUID releaseId);

    CatalogPage catalog(
            String projectId, UUID requestedReleaseId, String search, String kind, int page, int size);

    CatalogRepository.Resource resource(
            String projectId, UUID requestedReleaseId, String resourceId);

    Lineage lineage(String projectId, UUID requestedReleaseId, String resourceId, int limit);

    UUID resolvePublished(String projectId, UUID requestedReleaseId);
}
