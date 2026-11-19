package com.metricplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(properties = "metric-platform.operations.token=fixture-only")
@AutoConfigureMockMvc
class TenantAccountApiTest {
    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        redis.start();
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    private static final String ACCOUNTS = "/api/v1/tenant/accounts", LOGIN = "/api/v1/auth/token";
    private static final String TOKEN_TENANT_FIELD = "tenant_id";

    @Test
    void managerCreatesReaderWhoseSessionCannotCreateAccounts() throws Exception {
        String manager = account();
        String tenant =
                new ObjectMapper()
                        .readTree(
                                mvc.perform(
                                                post("/api/ops/tenants")
                                                        .header(
                                                                "X-Operations-Token",
                                                                "fixture-only")
                                                        .contentType("application/json")
                                                        .content(manager))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("tenant_id")
                        .asText();
        String managerToken = login(manager, tenant, "TENANT_MANAGER");
        // 运维请求即使同时携带有效业务令牌，仍按独立凭据开通新租户。
        mvc.perform(
                        post("/api/ops/tenants")
                                .header("Authorization", managerToken)
                                .header("X-Operations-Token", "fixture-only")
                                .contentType("application/json")
                                .content(account()))
                .andExpect(status().isCreated());
        String reader = account();
        mvc.perform(
                        post(ACCOUNTS)
                                .header("Authorization", managerToken)
                                .contentType("application/json")
                                .content(reader))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("READ_ONLY"));
        String readerToken = login(reader, tenant, "READ_ONLY");
        mvc.perform(
                        post(ACCOUNTS)
                                .header("Authorization", readerToken)
                                .contentType("application/json")
                                .content(account()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(tenant));

        // 升级前 Redis 未记录签发租户的令牌按账号定位；显式租户与账号不同时立即拒绝。
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                readerToken
                                                        .substring("Bearer ".length())
                                                        .getBytes(StandardCharsets.UTF_8)));
        String key = "metric-platform:auth-token:" + digest;
        assertThat(redis.opsForHash().get(key, TOKEN_TENANT_FIELD)).isEqualTo(tenant);
        redis.opsForHash().delete(key, TOKEN_TENANT_FIELD);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(tenant));
        redis.opsForHash()
                .put(key, TOKEN_TENANT_FIELD, tenant.equals("tn_99999") ? "tn_99998" : "tn_99999");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", readerToken))
                .andExpect(status().isUnauthorized());
    }

    private String login(String body, String tenant, String role) throws Exception {
        String response =
                mvc.perform(post(LOGIN).contentType("application/json").content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.identity.tenant_id").value(tenant))
                        .andExpect(jsonPath("$.identity.role").value(role))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return "Bearer " + new ObjectMapper().readTree(response).get("accessToken").asText();
    }

    private String account() {
        return "{\"username\":\"" + UUID.randomUUID() + "\",\"password\":\"fixture-password\"}";
    }
}
