package com.metricplatform.persistence.store;

import com.metricplatform.environment.AssetOwnership;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AssetOwnershipStore implements AssetOwnership {
    private static final String ENV =
            """
            select exists(select 1 from execution_environment where tenant_id=? and environment_id=?
            union all select 1 from build_ownership where tenant_id=? and environment_id=?)
            """;
    private static final String BUILD =
            "select exists(select 1 from build_ownership where tenant_id=? and build_id=?)";
    private static final String ENV_BUILD =
            "select exists(select 1 from build_ownership where tenant_id=? and environment_id=? and"
                + " build_id=?)";
    private static final String SECRET =
            "select cursor_secret from platform_tenant where tenant_id=?";
    private final JdbcTemplate jdbc;

    public boolean hasEnvironment(String tenant, UUID environment) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(ENV, Boolean.class, tenant, environment, tenant, environment));
    }

    public boolean hasBuild(String tenant, UUID build) {
        return Boolean.TRUE.equals(jdbc.queryForObject(BUILD, Boolean.class, tenant, build));
    }

    public boolean hasBuild(String tenant, UUID environment, UUID build) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(ENV_BUILD, Boolean.class, tenant, environment, build));
    }

    public UUID cursorSecret(String tenant) {
        return jdbc.queryForObject(SECRET, UUID.class, tenant);
    }
}
