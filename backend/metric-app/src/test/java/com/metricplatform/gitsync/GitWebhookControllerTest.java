package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GitWebhookControllerTest {
    private static final String SECRET = "test-only-hook-secret";
    private static final byte[] BODY =
            "{\"protocolVersion\":1,\"repositoryId\":\"metrics\",\"ref\":\"refs/heads/main\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void rejectsSignedNullAndMissingRef() throws Exception {
        var properties = new GitSyncProperties();
        properties.setRepositoryId("metrics");
        properties.setWebhookSecret(SECRET);
        var controller = new GitWebhookController(properties, mock(GitRefreshTrigger.class));
        for (var input :
                new String[] {
                    "null",
                    "{\"protocolVersion\":1,\"repositoryId\":\"metrics\"}",
                    "{\"protocolVersion\":1.9,\"repositoryId\":\"metrics\",\"ref\":\"refs/heads/main\"}",
                    "{\"protocolVersion\":\"1\",\"repositoryId\":\"metrics\",\"ref\":\"refs/heads/main\"}"
                }) {
            byte[] body = input.getBytes(StandardCharsets.UTF_8);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            assertThatThrownBy(() -> controller.accept(signature, body))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }

    @Test
    void validatesSignatureBeforeAcceptingNotification() throws Exception {
        var properties = new GitSyncProperties();
        properties.setRepositoryId("metrics");
        properties.setWebhookSecret(SECRET);
        var coordinator = mock(GitRefreshTrigger.class);
        var controller = new GitWebhookController(properties, coordinator);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(BODY));
        assertThat(controller.accept(signature, BODY).getStatusCode().value()).isEqualTo(202);
        assertThatThrownBy(
                        () -> controller.accept(signature, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.accept(null, BODY))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(coordinator, times(1)).requestRefresh();
    }
}
