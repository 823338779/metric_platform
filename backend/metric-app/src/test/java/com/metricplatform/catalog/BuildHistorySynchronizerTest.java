package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.api.BuildHistoryController;
import com.metricplatform.environment.AssetOwnership;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.TenantAssets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class BuildHistorySynchronizerTest {
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("12345678-1234-4234-8234-000000000001");

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void liveHistoryPropagatesServiceFailureWithoutReadingLegacyProjection() {
        var gateway = mock(BuildGateway.class);
        var ownership = mock(AssetOwnership.class);
        var actor =
                new PlatformPrincipal(
                        UUID.randomUUID(),
                        "manager",
                        "manager",
                        null,
                        1,
                        "tn_00001",
                        PlatformPrincipal.Role.TENANT_MANAGER);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        when(ownership.hasEnvironment(actor.tenantId(), ENVIRONMENT_ID)).thenReturn(true);
        when(ownership.cursorSecret(actor.tenantId())).thenReturn(UUID.randomUUID());
        var controller = new BuildHistoryController(gateway, new TenantAssets(ownership));
        var filter = new BuildFilter(ENVIRONMENT_ID, "repo", null, null, null, 50);
        var page = new BuildPage(List.of(), null);
        when(gateway.listBuilds(filter))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(page);
        assertThatThrownBy(() -> controller.list(ENVIRONMENT_ID, "repo", null, null, null, 50))
                .isInstanceOf(IllegalStateException.class);
        assertThat(controller.list(ENVIRONMENT_ID, "repo", null, null, null, 50)).isEqualTo(page);
        verify(gateway, times(2)).listBuilds(filter);
        assertThat(BuildGateway.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("changes"));
    }
}
