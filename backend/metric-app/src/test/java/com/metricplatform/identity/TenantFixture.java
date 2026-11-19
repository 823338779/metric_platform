package com.metricplatform.identity;

import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TenantFixture {
    public static final String ID = "tn_99001";
    private static final UUID MANAGER = UUID.fromString("99000000-0000-4000-8000-000000000001");

    public static PlatformPrincipal manager(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                do $$ begin
                    insert into platform_tenant values('tn_99001','99000000-0000-4000-8000-000000000001') on conflict do nothing;
                    insert into platform_user(id,tenant_id,username,display_name)
                    values('99000000-0000-4000-8000-000000000001','tn_99001','fixture-manager','fixture') on conflict do nothing;
                end $$;
                """);
        return new PlatformPrincipal(
                MANAGER,
                "fixture-manager",
                "fixture",
                null,
                1,
                ID,
                PlatformPrincipal.Role.TENANT_MANAGER);
    }

    public static PlatformPrincipal bind(JdbcTemplate jdbc, UUID env, UUID build) {
        var owner = manager(jdbc);
        jdbc.update("insert into build_ownership values(?,?,?)", build, env, ID);
        return owner;
    }

    private TenantFixture() {}
}
