package com.metricplatform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 短生命周期访问令牌只放 Redis，避免为每次认证读写 PostgreSQL。 */
@Service
public class RedisAccessTokenStore {
    private static final String PREFIX = "metric-platform:auth-token:";
    private final StringRedisTemplate redis;

    public RedisAccessTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String tokenHash, UUID tokenId, UUID identityId, long authVersion, Instant expiresAt) {
        String key = PREFIX + tokenHash;
        redis.opsForHash()
                .putAll(
                        key,
                        java.util.Map.of(
                                "id", tokenId.toString(),
                                "identityId", identityId.toString(),
                                "authVersion", Long.toString(authVersion)));
        redis.expire(key, Duration.between(Instant.now(), expiresAt));
    }

    public TokenData find(String tokenHash) {
        var values = redis.opsForHash().entries(PREFIX + tokenHash);
        if (values.isEmpty()) return null;
        return new TokenData(
                UUID.fromString((String) values.get("id")),
                UUID.fromString((String) values.get("identityId")),
                Long.parseLong((String) values.get("authVersion")));
    }

    public record TokenData(UUID id, UUID identityId, long authVersion) {}
}
