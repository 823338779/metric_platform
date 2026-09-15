package com.metricplatform.gitsync;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.identity.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 用实际 Spring 路由及安全过滤链验证内部签名与平台 Bearer 边界。 */
@SpringBootTest(
        properties = {
            "metric-platform.git-sync.enabled=true",
            "metric-platform.git-sync.repository-id=metrics",
            "metric-platform.git-sync.webhook-secret=test-only-hook-secret"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitSyncHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean GitSyncCoordinator coordinator;
    @MockitoBean GitImportPort importer;
    @MockitoBean PlatformAuthenticationService authentication;

    @Test
    void requiresHmacForWebhookAndBearerForStatus() throws Exception {
        byte[] body =
                "{\"protocolVersion\":1,\"repositoryId\":\"metrics\",\"ref\":\"refs/heads/main\"}"
                        .getBytes(StandardCharsets.UTF_8);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        "test-only-hook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        mvc.perform(
                        post(GitWebhookController.PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(GitWebhookController.PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-Git-Signature", signature))
                .andExpect(status().isAccepted());
        mvc.perform(
                        post(GitWebhookController.PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(new byte[4097]))
                .andExpect(status().isPayloadTooLarge());
        verify(coordinator).requestRefresh();
        mvc.perform(get("/api/v1/git-sync/status")).andExpect(status().isUnauthorized());
        when(authentication.authenticate("git-status-token"))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        when(importer.state())
                .thenReturn(new GitImportPort.State("metrics", "abc", "model", null, null, null));
        mvc.perform(
                        get("/api/v1/git-sync/status")
                                .header("Authorization", "Bearer git-status-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedCommit").value("abc"));
    }
}
