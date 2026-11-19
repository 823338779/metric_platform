package com.metricplatform.persistence.redis;

import com.metricplatform.identity.TenantScope;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 短生命周期访问令牌只放 Redis，避免为每次认证读写 PostgreSQL。 */
@Service
public class RedisAccessTokenStore {
    private static final String PREFIX = "metric-platform:auth-token:";
    private static final String ID = "id",
            IDENTITY = "identityId",
            VERSION = "authVersion",
            TENANT = "tenant_id";
    private final StringRedisTemplate redis;

    public RedisAccessTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(
            String tokenHash, UUID tokenId, UUID identityId, long authVersion, Instant expiresAt) {
        String key = PREFIX + tokenHash;
        redis.opsForHash()
                .putAll(
                        key,
                        java.util.Map.of(
                                ID, tokenId.toString(),
                                IDENTITY, identityId.toString(),
                                VERSION, Long.toString(authVersion),
                                TENANT, TenantScope.require()));
        redis.expire(key, Duration.between(Instant.now(), expiresAt));
    }

    /** 认证启动只读租户指针，旧格式的账号指针交由数据库确认。 */
    public String findTenant(String tokenHash, Function<UUID, String> legacyOwner) {
        var owner =
                redis.<String, String>opsForHash()
                        .multiGet(PREFIX + tokenHash, List.of(TENANT, IDENTITY));
        if (owner.get(0) != null) return owner.get(0);
        return owner.get(1) == null ? null : legacyOwner.apply(UUID.fromString(owner.get(1)));
    }

    public TokenData find(String tokenHash) {
        var values = redis.opsForHash().entries(PREFIX + tokenHash);
        if (values.isEmpty()) return null;
        return new TokenData(
                UUID.fromString((String) values.get(ID)),
                UUID.fromString((String) values.get(IDENTITY)),
                Long.parseLong((String) values.get(VERSION)),
                (String) values.get(TENANT));
    }

    public record TokenData(
            UUID id,
            UUID identityId,
            long authVersion,
            /** 签发时的租户，旧缓存由服务端账号归属补全。 */
            String tenantId) {}
}
