package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.EnvironmentInput;
import com.metricplatform.environment.EnvironmentContracts.SubmitBuildCommand;
import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TenantEnvironmentPersistenceTest {
    @Autowired EnvironmentStore environments;
    @Autowired BuildSubmissionStore submissions;
    @Autowired TenantAccountService accounts;
    @Autowired TenantFixtureMapper tenants;
    @Autowired EnvironmentFixtureMapper fixtures;

    @Test
    void foreignTenantCannotReadVersionsReuseSubmissionsOrClaimPhysicalTarget() throws Exception {
        var actor = TenantFixture.manager(tenants);
        String owner = actor.tenantId();
        String other =
                accounts.provision(
                        new AuthTokenRequest(UUID.randomUUID().toString(), "fixture-password"));
        String schema = "private_" + UUID.randomUUID().toString().replace("-", "");
        var input =
                new ObjectMapper()
                        .readValue(EnvironmentApiTest.input(schema), EnvironmentInput.class);
        String key = UUID.randomUUID().toString();
        var command =
                new SubmitBuildCommand(
                        "https://example.test/metrics.git", "main", null, 1, "NONE", key);
        UUID id, build = UUID.randomUUID();
        assertThatThrownBy(() -> environments.list(owner))
                .isInstanceOf(IllegalStateException.class);
        try (var scope = TenantScope.open(owner)) {
            id = environments.create(owner, input).environmentId();
            submissions.reserve(owner, actor.identityId(), id, command);
            assertThatThrownBy(() -> environments.list(other))
                    .isInstanceOf(IllegalStateException.class);
        }

        // 即使使用原提交者和幂等键，其他租户也无法恢复或确认原快照。
        try (var scope = TenantScope.open(other)) {
            assertThat(environments.list(other))
                    .extracting(EnvironmentContracts.EnvironmentView::environmentId)
                    .doesNotContain(id);
            assertThatThrownBy(() -> environments.get(other, id))
                    .isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> environments.update(other, id, 1, input))
                    .isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> environments.create(other, input))
                    .isInstanceOf(BuildGateway.Failure.class);
            assertThatThrownBy(() -> submissions.reserve(other, actor.identityId(), id, command))
                    .isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> submissions.accepted(other, actor.identityId(), key, build))
                    .isInstanceOf(BuildGateway.Failure.class);
        }
        try (var scope = TenantScope.open(owner)) {
            assertThat(environments.get(owner, id).configVersion()).isOne();
            submissions.accepted(owner, actor.identityId(), key, build);
        }
        assertThat(fixtures.recordedTenants(id, build)).containsExactly(owner, owner, owner, owner);
    }
}
