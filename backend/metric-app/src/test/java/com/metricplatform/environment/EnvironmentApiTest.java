package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties =
                "EXECUTION_INSTANCES={\"warehouse\":{\"type\":\"starrocks\",\"hosts\":[\"localhost\",\"127.0.0.1\"],\"port\":9030}}")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvironmentApiTest {
    private static final String ROOT = "/api/v4/dbt/environments";
    @Autowired MockMvc mvc;
    @MockitoBean PlatformAuthenticationService authentication;
    @MockitoBean EnvironmentBuildGateway gateway;

    @Test
    void buildResponsesSerializeTheServiceDocument() throws Exception {
        var mapper = new ObjectMapper();
        UUID build = UUID.randomUUID();
        var body = mapper.readTree("{\"buildId\":\"" + build + "\",\"buildStatus\":\"QUEUED\"}");
        when(gateway.getBuild(build)).thenReturn(body);
        when(gateway.logs(build, null))
                .thenReturn(mapper.readTree("{\"items\":[\"QUEUED\"],\"nextCursor\":null}"));
        when(gateway.history(any()))
                .thenReturn(mapper.readTree("{\"items\":[],\"nextCursor\":null}"));
        when(gateway.submit(any())).thenReturn(body);
        String created =
                mvc.perform(
                                post(ROOT)
                                        .header("Authorization", "Bearer environment-test")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                input(
                                                        "env_"
                                                                + UUID.randomUUID()
                                                                        .toString()
                                                                        .replace("-", ""))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String environment = mapper.readTree(created).path("environmentId").asText();
        mvc.perform(
                        get(ROOT + "/" + environment + "/builds")
                                .header("Authorization", "Bearer environment-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(
                        get("/api/v4/dbt/builds/" + build)
                                .header("Authorization", "Bearer environment-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()));
        mvc.perform(
                        get("/api/v4/dbt/builds/" + build + "/logs")
                                .header("Authorization", "Bearer environment-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("QUEUED"));
        mvc.perform(
                        post(ROOT + "/" + environment + "/builds")
                                .header("Authorization", "Bearer environment-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"repository":"https://example.test/metrics.git","branchName":"main","expectedConfigVersion":1,
                "deploymentPolicy":"NONE","idempotencyKey":"%s"}
                """
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value(build.toString()));
    }

    @Test
    void unknownTimezoneAndInlineConnectionPasswordAreRejected() throws Exception {
        mvc.perform(
                        post(ROOT)
                                .header("Authorization", "Bearer environment-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(input("test").replace("Asia/Shanghai", "Invalid/Unknown")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(
                        post(ROOT)
                                .header("Authorization", "Bearer environment-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        input("test")
                                                .replace(
                                                        "\"port\":9030",
                                                        "\"port\":9030,\"password\":\"not-a-real-secret\"")))
                .andExpect(status().is4xxClientError());
    }

    @BeforeEach
    void login() {
        when(authentication.authenticate("environment-test"))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
    }

    static String input(String schema) {
        return """
        {"name":"测试环境","connection":{"type":"starrocks","instanceId":"warehouse","host":"localhost","port":9030},
        "credentialRef":"test","catalog":"default_catalog","schema":"%s","threads":2,"businessTimezone":"Asia/Shanghai"}
        """
                .formatted(schema);
    }

    @Test
    void environmentRoutesRequireLogin() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    }

    @Test
    void createsEnvironmentWithoutSourceBindingAndRejectsDuplicatePhysicalTarget()
            throws Exception {
        String body = input("env_" + UUID.randomUUID().toString().replace("-", ""));
        var response =
                mvc.perform(
                                post(ROOT)
                                        .header("Authorization", "Bearer environment-test")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.configVersion").value(1))
                        .andExpect(jsonPath("$.execution.catalog").value("default_catalog"))
                        .andExpect(jsonPath("$.repository").doesNotExist())
                        .andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("password");
        mvc.perform(
                        post(ROOT)
                                .header("Authorization", "Bearer environment-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.replace("localhost", "127.0.0.1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_TARGET_CONFLICT"));
    }

    @Test
    void rejectsRepositoryInsideEnvironment() throws Exception {
        String body =
                input("test")
                        .replace(
                                "\"name\":",
                                "\"repository\":\"https://example.test/r.git\",\"name\":");
        mvc.perform(
                        post(ROOT)
                                .header("Authorization", "Bearer environment-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().is4xxClientError());
    }
}
