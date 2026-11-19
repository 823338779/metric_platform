package com.metricplatform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.environment.AssetOwnership;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantAssets {
    private static final String CIPHER = "AES/GCM/NoPadding", AES = "AES";
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AssetOwnership ownership;

    public void requireBuild(UUID build) {
        var actor = PlatformPrincipal.current();
        if (!actor.manager()) throw missing();
        requireTenantBuild(build);
    }

    /** 工作区允许查看本租户构建的版本信息，源码和日志仍单独限制。 */
    public void requireTenantBuild(UUID build) {
        if (!ownership.hasBuild(PlatformPrincipal.current().tenantId(), build)) throw missing();
    }

    public void requireEnvironment(UUID environment) {
        var actor = PlatformPrincipal.current();
        if (!ownership.hasEnvironment(actor.tenantId(), environment)) throw missing();
    }

    public boolean visible(UUID environment, UUID build) {
        return build != null
                && ownership.hasBuild(PlatformPrincipal.current().tenantId(), environment, build);
    }

    public static NoSuchElementException missing() {
        return new NoSuchElementException("资产不存在");
    }

    /** 平台向用户返回的页只包含已确认归属的记录及绑定筛选条件的游标。 */
    public record Page<T>(List<T> items, String nextCursor) {}

    public <T> Page<T> history(
            UUID environment,
            String cursor,
            Function<String, Page<T>> fetch,
            Predicate<T> visible,
            String... scope) {
        requireEnvironment(environment);
        var secret = ownership.cursorSecret(PlatformPrincipal.current().tenantId());
        String remote = cursor == null ? null : decode(cursor, secret, environment, scope);
        var seen = new HashSet<String>();
        while (seen.add(remote)) {
            var page = fetch.apply(remote);
            var items = page.items().stream().filter(visible).toList();
            remote = page.nextCursor();
            if (!items.isEmpty() || remote == null) {
                String next = remote == null ? null : encode(remote, secret, environment, scope);
                return new Page<>(items, next);
            }
        }
        throw new IllegalStateException("服务历史游标重复");
    }

    private static String encode(String remote, UUID secret, UUID environment, String[] scope) {
        // 上游游标可能含有被过滤的构建 ID，须加密而非仅签名。
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            byte[] encrypted =
                    cipher(Cipher.ENCRYPT_MODE, secret, environment, scope, nonce)
                            .doFinal(remote.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            ByteBuffer.allocate(nonce.length + encrypted.length)
                                    .put(nonce)
                                    .put(encrypted)
                                    .array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("分页加密失败", error);
        }
    }

    private static String decode(String cursor, UUID secret, UUID environment, String[] scope) {
        byte[] token = Base64.getUrlDecoder().decode(cursor);
        if (token.length < NONCE_BYTES + 16) throw new IllegalArgumentException("历史游标无效");
        var nonce = Arrays.copyOf(token, NONCE_BYTES);
        try {
            return new String(
                    cipher(Cipher.DECRYPT_MODE, secret, environment, scope, nonce)
                            .doFinal(token, NONCE_BYTES, token.length - NONCE_BYTES),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("历史游标与当前筛选不匹配");
        }
    }

    private static Cipher cipher(
            int mode, UUID secret, UUID environment, String[] scope, byte[] nonce)
            throws GeneralSecurityException {
        var key =
                ByteBuffer.allocate(16)
                        .putLong(secret.getMostSignificantBits())
                        .putLong(secret.getLeastSignificantBits())
                        .array();
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(mode, new SecretKeySpec(key, AES), new GCMParameterSpec(128, nonce));
        try {
            cipher.updateAAD(JSON.writeValueAsBytes(List.of(environment, scope)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("分页范围无法编码", error);
        }
        return cipher;
    }
}
