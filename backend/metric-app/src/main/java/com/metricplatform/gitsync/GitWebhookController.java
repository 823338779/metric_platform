package com.metricplatform.gitsync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

/** 仅接收经过共享密钥签名的唤醒通知；真实提交、路径和远端始终由固定配置读取。 */
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
        if (request.getContentLengthLong() > 4096)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            return accept(signature, request.getInputStream().readNBytes(4097));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git 通知读取失败");
        }
    }

    ResponseEntity<Void> accept(String signature, byte[] body) {
        if (body.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
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
}
