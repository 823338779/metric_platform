package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.TenantScope;
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
    @Autowired DeployedAssets deployed;
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
        // 模拟后台登记后打开授权页，切换账号读取列表不能再访问 dbt-service。
        try (var scope = TenantScope.open(tenant)) {
            deployed.refreshCatalog();
        }
        clearInvocations(gateway);
        String management = "/api/v1/tenant/accounts/" + reader + "/assets";
        var listed =
                mvc.perform(get(management).header("Authorization", "Bearer owner"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        var rows = json.readTree(listed);
        assertThat(rows.size()).isEqualTo(3);
        verifyNoInteractions(gateway);
        assertThat(rows.get(0).path("environmentName").asText()).isEqualTo(env.toString());
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
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer foreign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.granted == true)]", org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.granted == false)]", org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(get(management).header("Authorization", "Bearer foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(put(grant).header("Authorization", "Bearer foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(put(grant).header("Authorization", "Bearer reader"))
                .andExpect(status().isForbidden());
        mvc.perform(put(grant).header("Authorization", "Bearer owner"))
                .andExpect(status().isNoContent());
        // 同一时刻、同一租户，其他账号可以看见指标，但不能继承 reader 的授权。
        var otherReader = UUID.randomUUID();
        jdbc.update(
                "insert into platform_user(id,tenant_id,username,display_name) values(?,?,?,?)",
                otherReader,
                tenant,
                otherReader.toString(),
                "other-reader");
        when(auth.authenticate("other"))
                .thenReturn(actor(otherReader, tenant, PlatformPrincipal.Role.READ_ONLY));
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.granted == false)]", org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(
                        jsonPath(
                                "$[?(@.granted == true)].kind",
                                org.hamcrest.Matchers.contains("METRIC")))
                .andExpect(
                        jsonPath(
                                "$[?(@.granted == false)].kind",
                                org.hamcrest.Matchers.containsInAnyOrder("DATASET", "DIMENSION")))
                .andExpect(jsonPath("$[0].ownerId").value(owner.toString()));
        mvc.perform(
                        get("/api/dbt/builds/" + build + "/catalog")
                                .header("Authorization", "Bearer reader"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$[?(@.granted == true)].resource_type",
                                org.hamcrest.Matchers.contains("metric")))
                .andExpect(
                        jsonPath(
                                "$[?(@.granted == false)].resource_type",
                                org.hamcrest.Matchers.containsInAnyOrder("dimension", "model")));
        // 即使没有数据集和维度授权，只读用户仍能读取原生模型、源码、血缘和维度定义。
        String prefix = "/api/dbt/builds/" + build;
        String model = "semantic_model.shop.orders";
        mvc.perform(get(prefix + "/catalog").header("Authorization", "Bearer reader"))
                .andExpect(
                        jsonPath(
                                "$[?(@.unique_id == 'semantic_model.shop.orders')].name",
                                org.hamcrest.Matchers.contains("orders")))
                .andExpect(
                        jsonPath(
                                "$[?(@.resource_type == 'dimension')].semanticModelId",
                                org.hamcrest.Matchers.contains(model)));
        when(gateway.resource(build, model))
                .thenReturn(
                        new ResourceView(
                                build,
                                model,
                                Map.of("name", "orders"),
                                new ResourceSource("orders.yml", "orders definition")));
        mvc.perform(get(prefix + "/resources/" + model).header("Authorization", "Bearer other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.content").value("orders definition"));
        when(gateway.lineage(build, "orders"))
                .thenReturn(json.valueToTree(List.of(Map.of("name", "orders"))));
        mvc.perform(
                        get(prefix + "/lineage")
                                .param("selector", "orders")
                                .header("Authorization", "Bearer other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("orders"));
        var options = new AvailableDimensionsRequest(List.of("sales"));
        when(gateway.availableDimensions(build, options))
                .thenReturn(
                        new AvailableDimensionsView(
                                build,
                                options.metrics(),
                                List.of(
                                        Map.of(
                                                "name",
                                                "region",
                                                "semanticModelName",
                                                "orders",
                                                "optionId",
                                                "region"))));
        mvc.perform(
                        post(prefix + "/available-dimensions")
                                .header("Authorization", "Bearer other")
                                .contentType("application/json")
                                .content("{\"metrics\":[\"sales\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions[0].name").value("region"))
                .andExpect(jsonPath("$.dimensions[0].granted").value(false));
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
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.granted == false)]", org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content(summary))
                .andExpect(status().isForbidden());
        // 撤销指标授权后仍可编译 SQL，实际取数继续被拒绝。
        when(gateway.query(eq(build), argThat(q -> q.mode() == QueryMode.EXPLAIN)))
                .thenReturn(new ResultPage(build, QueryMode.EXPLAIN, null, "UTC", Map.of(),
                        "select 1", null, null, null));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content("{\"mode\":\"EXPLAIN\",\"metrics\":[\"sales\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value("select 1"))
                .andExpect(jsonPath("$.rows").doesNotExist());

        when(current.activeBuildId()).thenReturn(null);
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer reader")
                                .contentType("application/json")
                                .content(summary))
                .andExpect(status().isGone());
        verify(gateway, times(2)).query(eq(build), any());
    }

    private PlatformPrincipal actor(UUID id, String tenant, PlatformPrincipal.Role role) {
        return new PlatformPrincipal(id, "fixture", "fixture", null, 1, tenant, role);
    }
}
