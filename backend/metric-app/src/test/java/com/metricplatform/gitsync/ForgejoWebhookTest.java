package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ForgejoWebhookTest {
    private static final String PATH = "/internal/git-sync/events";
    private static final String SIGNATURE = "X-Forgejo-Signature";
    private static final String EVENT = "X-Forgejo-Event";
    private static final String PUSH = "push";
    private static final String PING = "ping";
    private static final String MAIN = "refs/heads/main";
    private static final String FEATURE = "refs/heads/feature";
    private static final String REPOSITORY = "agent-admin/metrics";
    private static final String OTHER_REPOSITORY = "agent-admin/other";
    private static final String BEFORE = "1111111111111111111111111111111111111111";
    private static final String AFTER = "2222222222222222222222222222222222222222";
    private static final String DELETED = "0000000000000000000000000000000000000000";
    private static final String EMPTY = "";
    private static final String PADDING = "x";
    private static final String INVALID = "not-json";
    private static final String BODY =
            """
            {"ref":"%s","before":"%s","after":"%s",
             "repository":{"full_name":"%s"},"commits":[],"extra":"%s"}
            """;
    private final AtomicInteger signals = new AtomicInteger();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "hook.repository-id",
                                "metrics_store",
                                "hook.forgejo-repository",
                                REPOSITORY));
        var properties =
                new Binder(source).bind("hook", Bindable.of(GitSyncProperties.class)).get();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new GitWebhookController(properties, signals::incrementAndGet))
                        .build();
    }

    @Test
    void acceptsNativeMainPushLargerThanLegacyLimit() throws Exception {
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, AFTER, PADDING.repeat(5000)), true))
                .isEqualTo(202);
        assertThat(signals.get()).isEqualTo(1);
    }

    @Test
    void ignoresOtherBranchesDeletionAndUnchangedHead() throws Exception {
        assertThat(send(PUSH, payload(FEATURE, REPOSITORY, AFTER, EMPTY), true)).isEqualTo(202);
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, DELETED, EMPTY), true)).isEqualTo(202);
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, BEFORE, EMPTY), true)).isEqualTo(202);
        assertThat(send(PING, payload(MAIN, REPOSITORY, AFTER, EMPTY), true)).isEqualTo(202);
        assertThat(signals.get()).isZero();
    }

    @Test
    void rejectsWrongRepositoryAndMalformedPayload() throws Exception {
        assertThat(send(PUSH, payload(MAIN, OTHER_REPOSITORY, AFTER, EMPTY), true)).isEqualTo(400);
        assertThat(send(PUSH, INVALID, true)).isEqualTo(400);
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, EMPTY, EMPTY), true)).isEqualTo(400);
        assertThat(signals.get()).isZero();
    }

    @Test
    void acceptsPushWithoutSecretSignatureOrAuthorization() throws Exception {
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, AFTER, EMPTY), false)).isEqualTo(202);
        assertThat(signals.get()).isEqualTo(1);
    }

    @Test
    void boundsNativePayloadBeforeParsing() throws Exception {
        assertThat(send(PUSH, payload(MAIN, REPOSITORY, AFTER, PADDING.repeat(1024 * 1024)), true))
                .isEqualTo(413);
        assertThat(signals.get()).isZero();
    }

    private static String payload(String ref, String repository, String after, String extra) {
        return BODY.formatted(ref, BEFORE, after, repository, extra);
    }

    private int send(String event, String body, boolean includeIgnoredSignature) throws Exception {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var request =
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes)
                        .header(EVENT, event);
        if (includeIgnoredSignature) request.header(SIGNATURE, INVALID);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
