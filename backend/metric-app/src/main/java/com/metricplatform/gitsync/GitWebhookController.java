package com.metricplatform.gitsync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Forgejo 通知无需鉴权；真实提交、路径和远端始终由固定配置读取。 */
@RestController
@ConditionalOnExpression("'${metric.publication.mode:legacy}' == 'legacy'")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "metric-platform.git-sync", name = "enabled", havingValue = "true")
public class GitWebhookController {
    public static final String PATH = "/internal/git-sync/events";
    private static final String HEADER = "X-Git-Signature",
            PREFIX = "sha256=",
            ALGORITHM = "HmacSHA256",
            MAIN = "refs/heads/main";
    private static final String FORGEJO_SIGNATURE = "X-Forgejo-Signature";
    private static final String FORGEJO_EVENT = "X-Forgejo-Event";
    private static final String PUSH = "push";
    private static final String REPOSITORY_POINTER = "/repository/full_name";
    private static final String REF = "ref", BEFORE = "before", AFTER = "after";
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    private static final String DELETED_SHA = "0000000000000000000000000000000000000000";
    private static final int LEGACY_BODY_LIMIT = 4096;
    private static final int FORGEJO_BODY_LIMIT = 1024 * 1024;
    private final GitSyncProperties properties;
    private final GitRefreshTrigger coordinator;
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Event(
            int protocolVersion,
            String eventId,
            String repositoryId,
            String ref,
            String before,
            String after) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestHeader(value = HEADER, required = false) String signature,
            HttpServletRequest request) {
        // 在反序列化和签名校验前有界读取，防止匿名请求分配任意大小的 byte[]。
        boolean forgejo =
                request.getHeader(FORGEJO_EVENT) != null
                        || request.getHeader(FORGEJO_SIGNATURE) != null;
        int limit = forgejo ? FORGEJO_BODY_LIMIT : LEGACY_BODY_LIMIT;
        if (request.getContentLengthLong() > limit)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            return forgejo
                    ? acceptForgejo(request.getHeader(FORGEJO_EVENT), body)
                    : accept(signature, body);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git 通知读取失败");
        }
    }

    ResponseEntity<Void> accept(String signature, byte[] body) {
        if (body.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        verifySignature(signature, body);
        Event event;
        try {
            event = mapper.readValue(body, Event.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git 通知格式无效");
        }
        if (event == null
                || event.ref() == null
                || event.ref().isBlank()
                || event.protocolVersion() != 1
                || !properties.getRepositoryId().equals(event.repositoryId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git 通知版本、分支或仓库不匹配");
        if (MAIN.equals(event.ref())) coordinator.requestRefresh();
        return ResponseEntity.accepted().build();
    }

    private ResponseEntity<Void> acceptForgejo(String event, byte[] body) {
        // Forgejo 入口不要求密钥或登录身份，载荷仍只能唤醒固定仓库。
        if (event == null || event.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Forgejo 事件类型缺失");
        }
        if (!PUSH.equals(event)) return ResponseEntity.accepted().build();
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Forgejo 通知格式无效");
        }
        String repository = properties.getForgejoRepository();
        if (payload == null
                || repository == null
                || repository.isBlank()
                || !repository.equals(payload.at(REPOSITORY_POINTER).textValue())
                || !payload.path(REF).isTextual()
                || payload.path(REF).textValue().isBlank()
                || !payload.path(BEFORE).isTextual()
                || !payload.path(BEFORE).textValue().matches(SHA_PATTERN)
                || !payload.path(AFTER).isTextual()
                || !payload.path(AFTER).textValue().matches(SHA_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Forgejo 仓库、分支或提交格式无效");
        }
        // 通知只唤醒固定远端读取；删除、未更新和其他分支不发起发布。
        String after = payload.path(AFTER).textValue();
        if (MAIN.equals(payload.path(REF).textValue())
                && !DELETED_SHA.equals(after)
                && !after.equals(payload.path(BEFORE).textValue())) {
            coordinator.requestRefresh();
        }
        return ResponseEntity.accepted().build();
    }

    private void verifySignature(String signature, byte[] body) {
        try {
            if (signature == null || !signature.startsWith(PREFIX))
                throw new IllegalArgumentException();
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(
                    new SecretKeySpec(
                            properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                            ALGORITHM));
            if (!MessageDigest.isEqual(
                    mac.doFinal(body),
                    HexFormat.of().parseHex(signature.substring(PREFIX.length()))))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Git 通知签名无效");
        }
    }
}
