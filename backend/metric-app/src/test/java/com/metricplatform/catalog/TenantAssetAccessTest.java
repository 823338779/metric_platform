package com.metricplatform.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TenantAssetAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PlatformAuthenticationService auth;
    @MockitoBean BuildGateway builds;
    @MockitoBean EnvironmentBuildGateway environments;

    @Test
    void unconfiguredOperationsEndpointRejectsAnyCredential() throws Exception {
        mvc.perform(
                        post("/api/ops/tenants")
                                .header("X-Operations-Token", "unconfigured")
                                .contentType("application/json")
                                .content("{\"username\":\"n\",\"password\":\"fixture\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignBuildsCannotReachAssetGateways() throws Exception {
        var id = UUID.randomUUID();
        var actor =
                new PlatformPrincipal(
                        id,
                        "test",
                        "test",
                        null,
                        1,
                        "tn_00002",
                        PlatformPrincipal.Role.TENANT_MANAGER);
        when(auth.authenticate("fixture")).thenReturn(actor);
        TenantFixture.bind(jdbc, UUID.randomUUID(), id);
        var path = "/api/dbt/builds/" + id;
        for (String suffix :
                List.of(
                        "",
                        "/catalog",
                        "/execution-logs",
                        "/resources/example",
                        "/lineage?selector=test"))
            mvc.perform(get(path + suffix).header("Authorization", "Bearer fixture"))
                    .andExpect(status().isNotFound());
        for (String suffix : List.of("/queries", "/available-dimensions"))
            mvc.perform(
                            post(path + suffix)
                                    .header("Authorization", "Bearer fixture")
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isNotFound());
        verifyNoInteractions(builds, environments);
    }
}
