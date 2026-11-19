package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.identity.TenantFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TenantBuildOwnershipTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BuildSubmissionStore submissions;
    @Autowired AssetOwnership assets;

    @Test
    void conflictingConfirmationRollsBackTheSubmissionUpdate() {
        var manager = TenantFixture.manager(jdbc);
        UUID build = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        TenantFixture.bind(jdbc, first, build);
        jdbc.update(
                "insert into execution_environment(environment_id,tenant_id,name,config_version)"
                        + " values(?,?,?,1)",
                second,
                manager.tenantId(),
                "fixture");
        jdbc.update(
                "insert into"
                        + " environment_build_submission(actor_id,idempotency_key,environment_id,command,request)"
                        + " values(?,'retry',?,'{}','{}')",
                manager.identityId(),
                second);
        assertThatThrownBy(() -> submissions.accepted(manager.identityId(), "retry", build))
                .isInstanceOf(BuildGateway.Failure.class);
        assertThat(
                        jdbc.queryForObject(
                                "select build_id from environment_build_submission where actor_id=?"
                                        + " and idempotency_key='retry'",
                                UUID.class,
                                manager.identityId()))
                .isNull();
        assertThat(assets.hasBuild(manager.tenantId(), first, build)).isTrue();
        assertThat(assets.hasBuild(manager.tenantId(), second, build)).isFalse();
    }
}
