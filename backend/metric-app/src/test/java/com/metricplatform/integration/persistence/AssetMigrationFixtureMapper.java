package com.metricplatform.integration.persistence;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

interface AssetMigrationFixtureMapper {
    void submission(
            @Param("tenant") String tenant,
            @Param("actor") UUID actor,
            @Param("environment") UUID environment,
            @Param("repository") String repository,
            @Param("branch") String branch,
            @Param("build") UUID build);

    void reader(
            @Param("tenant") String tenant,
            @Param("reader") UUID reader,
            @Param("name") String name);

    void grantBuildMembers(
            @Param("tenant") String tenant,
            @Param("reader") UUID reader,
            @Param("build") UUID build);

    void legacyScope(
            @Param("tenant") String tenant,
            @Param("build") UUID build,
            @Param("environment") UUID environment,
            @Param("source") String source);

    void legacyMetric(
            @Param("tenant") String tenant,
            @Param("asset") UUID asset,
            @Param("owner") UUID owner,
            @Param("environment") UUID environment,
            @Param("source") String source);

    void legacyMember(
            @Param("tenant") String tenant, @Param("build") UUID build, @Param("asset") UUID asset);
}
