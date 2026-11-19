package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.ErrorView;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentContracts.*;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.security.PlatformPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        properties = {
            "EXECUTION_INSTANCES={\"warehouse\":{\"type\":\"starrocks\",\"hosts\":[\"localhost\",\"127.0.0.1\"],\"port\":9030}}"
        })
@ActiveProfiles("test")
class EnvironmentBuildServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EnvironmentService environments;
    @Autowired EnvironmentBuildService builds;
    @MockitoBean EnvironmentBuildGateway gateway;

    private PlatformPrincipal actor;
    private TenantScope scope;

    @BeforeEach
    void login() {
        actor = TenantFixture.manager(jdbc);
        scope = TenantScope.open(actor.tenantId());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @AfterEach
    void logout() {
        scope.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void staleUpdatesAndSubmissionsKeepOldNamespaceReserved() throws Exception {
        String schema = "env_stale";
        var input =
                new ObjectMapper()
                        .readValue(EnvironmentApiTest.input(schema), EnvironmentInput.class);
        var environment = environments.create(input);
        var changed =
                new EnvironmentInput(
                        input.name(),
                        input.connection(),
                        input.credentialRef(),
                        input.catalog(),
                        schema + "_next",
                        input.threads(),
                        input.businessTimezone());
        assertThat(environments.update(environment.environmentId(), 1, changed).configVersion())
                .isEqualTo(2);
        assertThatThrownBy(() -> environments.update(environment.environmentId(), 1, input))
                .isInstanceOfSatisfying(
                        BuildGateway.Failure.class,
                        error ->
                                assertThat(error.error().code())
                                        .isEqualTo("ENVIRONMENT_VERSION_CONFLICT"));
        assertThatThrownBy(() -> environments.create(input))
                .isInstanceOfSatisfying(
                        BuildGateway.Failure.class,
                        error ->
                                assertThat(error.error().code())
                                        .isEqualTo("EXECUTION_TARGET_CONFLICT"));
        var command =
                new SubmitBuildCommand(
                        "https://example.test/metrics.git",
                        "main",
                        null,
                        1,
                        "NONE",
                        UUID.randomUUID().toString());
        assertThatThrownBy(() -> builds.submit(environment.environmentId(), command, actor))
                .isInstanceOfSatisfying(
                        BuildGateway.Failure.class,
                        error ->
                                assertThat(error.error().code())
                                        .isEqualTo("ENVIRONMENT_VERSION_CONFLICT"));
        verifyNoInteractions(gateway);
    }

    @Test
    void retryAfterTimeoutReusesOriginalSnapshot() throws Exception {
        String schema = "env_retry";
        var input =
                new ObjectMapper()
                        .readValue(EnvironmentApiTest.input(schema), EnvironmentInput.class);
        var environment = environments.create(input);

        var command =
                new SubmitBuildCommand(
                        "https://example.test/metrics.git",
                        "feature/test",
                        null,
                        1,
                        "NONE",
                        UUID.randomUUID().toString());
        when(gateway.submit(any()))
                .thenThrow(
                        new BuildGateway.Failure(
                                503, new ErrorView("UNAVAILABLE", "timeout", true, null, null)));
        assertThatThrownBy(() -> builds.submit(environment.environmentId(), command, actor))
                .isInstanceOf(BuildGateway.Failure.class);
        environments.update(
                environment.environmentId(),
                1,
                new EnvironmentInput(
                        input.name(),
                        input.connection(),
                        input.credentialRef(),
                        input.catalog(),
                        schema + "_new",
                        input.threads(),
                        input.businessTimezone()));
        var response =
                new ObjectMapper().createObjectNode().put("buildId", UUID.randomUUID().toString());
        doReturn(response).when(gateway).submit(any());
        assertThat(builds.submit(environment.environmentId(), command, actor)).isEqualTo(response);
        var requests = org.mockito.ArgumentCaptor.forClass(SnapshotBuildRequest.class);
        verify(gateway, times(2)).submit(requests.capture());
        assertThat(requests.getAllValues().get(1)).isEqualTo(requests.getAllValues().getFirst());
        assertThat(requests.getValue().execution().schema()).isEqualTo(schema);
        var changed =
                new SubmitBuildCommand(
                        command.repository(), "other", null, 1, "NONE", command.idempotencyKey());
        assertThatThrownBy(() -> builds.submit(environment.environmentId(), changed, actor))
                .isInstanceOfSatisfying(
                        BuildGateway.Failure.class,
                        error -> assertThat(error.status()).isEqualTo(409));
    }
}
