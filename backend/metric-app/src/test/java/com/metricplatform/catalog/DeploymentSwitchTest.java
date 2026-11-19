package com.metricplatform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.AssetOwnership;
import com.metricplatform.identity.TenantScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DeploymentSwitchTest {
    @Test
    void rejectsBuildReplacedDuringCatalogRegistration() {
        var store = mock(AssetGrantRepository.class);
        var ownership = mock(AssetOwnership.class);
        var gateway = mock(BuildGateway.class);
        var key = new DeploymentKey("repo", UUID.randomUUID(), "main");
        var current = mock(DeploymentTargetView.class);
        var build = UUID.randomUUID();
        when(current.branchName()).thenReturn(key.branchName());
        when(current.repository()).thenReturn(key.repository());
        when(current.environmentId()).thenReturn(key.environmentId());
        when(current.activeBuildId()).thenReturn(build, null);
        when(gateway.current(key)).thenReturn(current);
        when(store.target(build)).thenReturn(key);
        when(gateway.catalog(build)).thenReturn(new ObjectMapper().createArrayNode());
        try (var scope = TenantScope.open("tn_99001")) {
            when(ownership.hasBuild(TenantScope.require(), key.environmentId(), build))
                    .thenReturn(true);
            assertEquals(
                    410,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            new DeployedAssets(store, ownership, gateway)
                                                    .current(key))
                            .getStatusCode()
                            .value());
        }
    }
}
