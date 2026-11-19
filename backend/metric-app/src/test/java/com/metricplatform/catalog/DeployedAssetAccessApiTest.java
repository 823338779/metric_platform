package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DeployedAssetAccessApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantAccountService accounts;
    @MockitoBean PlatformAuthenticationService auth;
    @MockitoBean BuildGateway gateway;
    @MockitoBean EnvironmentBuildGateway environments;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void grantsFromDeployedCatalogAndRejectsUnauthorizedQueriesBeforeExecution() throws Exception {
        var env = UUID.randomUUID();
        var build = UUID.randomUUID();
        var key = new DeploymentKey("https://example.test/" + env, env, "main");
        var tenant = accounts.provision(new AuthTokenRequest(env.toString(), "fixture-password"));
        var owner =
                jdbc.queryForObject(
                        "select manager_id from platform_tenant where tenant_id=?",
                        UUID.class,
                        tenant);
        var reader = UUID.randomUUID();
        jdbc.update(
                "insert into platform_user(id,tenant_id,username,display_name) values(?,?,?,?)",
                reader,
                tenant,
                reader.toString(),
                "reader");
        jdbc.update("insert into build_ownership values(?,?,?)", build, env, tenant);
        when(auth.authenticate("owner"))
                .thenReturn(actor(owner, tenant, PlatformPrincipal.Role.TENANT_MANAGER));
        when(auth.authenticate("reader"))
                .thenReturn(actor(reader, tenant, PlatformPrincipal.Role.READ_ONLY));
        var fixed = mock(BuildView.class, RETURNS_DEEP_STUBS);
        when(fixed.buildId()).thenReturn(build);
        when(fixed.execution().environmentId()).thenReturn(env);
        when(fixed.repository()).thenReturn(key.repository());
        when(fixed.branchName()).thenReturn(key.branchName());
        when(gateway.getBuild(build)).thenReturn(fixed);
        var current = mock(DeploymentTargetView.class);
        when(current.environmentId()).thenReturn(env);
        when(current.repository()).thenReturn(key.repository());
        when(current.branchName()).thenReturn(key.branchName());
        when(current.activeBuildId()).thenReturn(build);
        when(gateway.current(key)).thenReturn(current);
        when(gateway.catalog(build))
                .thenReturn(
                        json.readTree(
                                """
                                [{"resource_type":"metric","unique_id":"metric.shop.sales","name":"sales"},
                                 {"resource_type":"model","unique_id":"model.shop.orders","name":"orders","fqn":["shop","orders"]},
                                 {"resource_type":"semantic_model","unique_id":"semantic_model.shop.orders","name":"orders",
                                  "dimensions":[{"name":"region"}]}]
                                """));
        String management = "/api/v1/tenant/accounts/" + reader + "/assets";
        var listed =
                mvc.perform(get(management).header("Authorization", "Bearer owner"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        var rows = json.readTree(listed);
        assertThat(rows.size()).isEqualTo(3);
        var metric = rows.findValuesAsText("assetId").get(0);
        for (var item : rows)
            if (item.path("kind").asText().equals("METRIC")) metric = item.path("assetId").asText();
        String grant = management + "/" + metric;
        String foreignTenant =
                accounts.provision(
                        new AuthTokenRequest(UUID.randomUUID().toString(), "fixture-password"));
        UUID foreign =
                jdbc.queryForObject(
                        "select manager_id from platform_tenant where tenant_id=?",
                        UUID.class,
                        foreignTenant);
        when(auth.authenticate("foreign"))
                .thenReturn(actor(foreign, foreignTenant, PlatformPrincipal.Role.TENANT_MANAGER));
        mvc.perform(get(management).header("Authorization", "Bearer foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(put(grant).header("Authorization", "Bearer foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(put(grant).header("Authorization", "Bearer reader"))
                .andExpect(status().isForbidden());
        mvc.perform(put(grant).header("Authorization", "Bearer owner"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerId").value(owner.toString()));
        String path = "/api/dbt/builds/" + build + "/queries";
        String summary = "{\"mode\":\"QUERY\",\"metrics\":[\"sales\"]}";
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.QUERY,
                                null,
                                "UTC",
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                false));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content(summary))
                .andExpect(status().isOk());
        for (String denied :
                List.of(
                        "{\"mode\":\"QUERY\",\"metrics\":[\"sales\",\"other\"]}",
                        "{\"mode\":\"PREVIEW\",\"selector\":\"orders\"}")) {
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer reader")
                                    .contentType("application/json")
                                    .content(denied))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(org.hamcrest.Matchers.containsString("无权限")));
        }
        mvc.perform(delete(grant).header("Authorization", "Bearer owner"))
                .andExpect(status().isNoContent());
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content(summary))
                .andExpect(status().isForbidden());
        when(current.activeBuildId()).thenReturn(null);
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content(summary))
                .andExpect(status().isGone());
        verify(gateway, times(1)).query(eq(build), any());
    }

    private PlatformPrincipal actor(UUID id, String tenant, PlatformPrincipal.Role role) {
        return new PlatformPrincipal(id, "fixture", "fixture", null, 1, tenant, role);
    }
}
