package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.AssetGrantRepository;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.EnvironmentInput;
import com.metricplatform.environment.EnvironmentContracts.SubmitBuildCommand;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantFixtureMapper;
import com.metricplatform.identity.TenantScope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TenantBuildOwnershipTest {
    @Autowired TenantFixtureMapper tenants;
    @Autowired EnvironmentFixtureMapper fixtures;
    @Autowired BuildSubmissionStore submissions;
    @Autowired AssetOwnership assets;
    @Autowired AssetGrantRepository catalog;
    @Autowired EnvironmentStore environments;

    @Test
    void confirmationBindsTheFixedTargetWithoutPublishingItAndRejectsReassignment()
            throws Exception {
        var manager = TenantFixture.manager(tenants);
        var tenant = manager.tenantId();
        var actor = manager.identityId();
        var build = UUID.randomUUID();
        var input =
                new ObjectMapper()
                        .readValue(
                                EnvironmentApiTest.input("accepted_target"),
                                EnvironmentInput.class);
        try (var scope = TenantScope.open(tenant)) {
            var environment = environments.create(tenant, input).environmentId();
            var repo = "https://example.test/metrics.git";
            var target = new DeploymentKey(repo, environment, "main");
            var first = new SubmitBuildCommand(repo, "main", null, 1, "NONE", build.toString());
            submissions.reserve(tenant, actor, environment, first);
            submissions.accepted(tenant, actor, first.idempotencyKey(), build);
            submissions.accepted(tenant, actor, first.idempotencyKey(), build);
            assertThat(catalog.target(build)).isEqualTo(target);
            assertThat(catalog.ready(build)).isFalse();
            assertThat(fixtures.observedBuildIds(environment)).isEmpty();

            var other =
                    new SubmitBuildCommand(
                            repo, "other", null, 1, "NONE", UUID.randomUUID().toString());
            submissions.reserve(tenant, actor, environment, other);
            assertThatThrownBy(
                            () ->
                                    submissions.accepted(
                                            tenant, actor, other.idempotencyKey(), build))
                    .isInstanceOfSatisfying(
                            BuildGateway.Failure.class,
                            e ->
                                    assertThat(e.error().code())
                                            .isEqualTo("BUILD_OWNERSHIP_CONFLICT"));
            assertThat(catalog.target(build)).isEqualTo(target);
            assertThat(fixtures.submissionBuildIds(actor, other.idempotencyKey()))
                    .containsExactly((UUID) null);
        }
    }

    @Test
    void conflictingConfirmationRollsBackTheSubmissionUpdate() {
        var manager = TenantFixture.manager(tenants);
        UUID build = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        TenantFixture.bind(tenants, first, build);
        try (var scope = TenantScope.open(manager.tenantId())) {
            fixtures.insertEnvironment(second);
            fixtures.insertSubmission(second, manager.identityId(), "retry");
            assertThatThrownBy(
                            () ->
                                    submissions.accepted(
                                            manager.tenantId(),
                                            manager.identityId(),
                                            "retry",
                                            build))
                    .isInstanceOf(BuildGateway.Failure.class);
            assertThat(fixtures.submissionBuildIds(manager.identityId(), "retry"))
                    .containsExactly((UUID) null);
            assertThat(assets.hasBuild(manager.tenantId(), first, build)).isTrue();
            assertThat(assets.hasBuild(manager.tenantId(), second, build)).isFalse();
        }
    }
}
