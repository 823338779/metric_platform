package com.metricplatform.identity;

import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;

public final class TenantFixture {
    public static final String ID = "tn_99001";
    private static final UUID MANAGER = UUID.fromString("99000000-0000-4000-8000-000000000001");

    public static PlatformPrincipal manager(TenantFixtureMapper fixtures) {
        fixtures.ensureManager(ID, MANAGER);
        return new PlatformPrincipal(
                MANAGER,
                "fixture-manager",
                "fixture",
                null,
                1,
                ID,
                PlatformPrincipal.Role.TENANT_MANAGER);
    }

    public static PlatformPrincipal bind(TenantFixtureMapper fixtures, UUID env, UUID build) {
        var owner = manager(fixtures);
        fixtures.bindBuild(ID, build, env);
        return owner;
    }

    private TenantFixture() {}
}
