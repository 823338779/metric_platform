package com.metricplatform.integration.persistence;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

interface TenantMigrationFixtureMapper {
    String tenantTable();

    int countTenants();

    long nextTenantNumber();

    void legacyAccount(
            @Param("id") UUID id,
            @Param("login") String login,
            @Param("displayName") String displayName);

    void createInventory();

    void emptyInventory(@Param("managerLogin") String managerLogin);

    void legacyQuery(@Param("build") UUID build, @Param("user") UUID user);

    void setInventoryBuild(@Param("environment") UUID environment, @Param("build") UUID build);

    String buildTenant(@Param("build") UUID build);

    UUID tenantManager(@Param("tenant") String tenant);

    int countUsers(@Param("tenant") String tenant);

    void legacyEvent(@Param("event") UUID event, @Param("account") UUID account);

    void createTenant(@Param("tenant") String tenant, @Param("manager") UUID manager);

    void createEnvironment(
            @Param("tenant") String tenant,
            @Param("environment") UUID environment,
            @Param("name") String name);

    void ownBuild(
            @Param("tenant") String tenant,
            @Param("environment") UUID environment,
            @Param("build") UUID build);

    void legacySubmission(
            @Param("actor") UUID actor,
            @Param("environment") UUID environment,
            @Param("build") UUID build);

    void archiveSubmissions();

    UUID eventIdentity(@Param("event") UUID event);

    void legacySoftDeleteEvent(@Param("event") UUID event);

    boolean recoveredDeletionTime(@Param("event") UUID event);

    void addAuditTenantColumn();

    void assignAuditEvent(@Param("event") UUID event, @Param("tenant") String tenant);

    List<String> invalidOwnerColumns();

    long countHistoryCursors();

    String anonymousEventTenant(@Param("event") UUID event, @Param("account") UUID account);

    List<String> migratedEvidenceTenants();

    void recordQuery(
            @Param("tenant") String tenant, @Param("build") UUID build, @Param("user") UUID user);

    void createProject(@Param("tenant") String tenant);

    void reassignProjects(
            @Param("current") String current, @Param("replacement") String replacement);
}
