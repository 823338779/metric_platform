package com.metricplatform.integration.persistence;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface HistoryProjectionFixtureMapper {
    void deleteOwnership(@Param("buildId") UUID buildId);

    void insertLegacyProject(@Param("projectId") String projectId);

    void insertFailedRelease(
            @Param("releaseId") UUID releaseId,
            @Param("projectId") String projectId,
            @Param("sha") String sha,
            @Param("idempotencyKey") UUID idempotencyKey);

    void insertFailedQuery(
            @Param("queryId") UUID queryId,
            @Param("projectId") String projectId,
            @Param("releaseId") UUID releaseId,
            @Param("idempotencyKey") UUID idempotencyKey);

    UUID queryBuild(@Param("queryId") UUID queryId);

    UUID queryRelease(@Param("queryId") UUID queryId);

    boolean importLegacyMapping(
            @Param("tenantId") String tenantId,
            @Param("releaseId") UUID releaseId,
            @Param("buildId") UUID buildId,
            @Param("repository") String repository,
            @Param("evidence") String evidence);

    void deleteLegacyMapping(@Param("releaseId") UUID releaseId);
}
