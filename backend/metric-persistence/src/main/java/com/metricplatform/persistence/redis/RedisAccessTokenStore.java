package com.metricplatform.persistence.redis;

import com.metricplatform.identity.PlatformIdentityRepository.Token;
import com.metricplatform.identity.TenantScope;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Redis 按令牌摘要定位租户，绑定租户后才能读取签发信息。 */
@Service
public class RedisAccessTokenStore {
    private static final String PREFIX = "metric-platform:auth-token:";
    private static final String IDENTITY = "identityId", TENANT = "tenant_id";
    private static final DefaultRedisScript<Long> SAVE =
            new DefaultRedisScript<>(
                    """
                    if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('HGET', KEYS[1], 'tenant_id') ~= ARGV[1] then
                        return 0
                    end
                    redis.call('HSET', KEYS[1], 'tenant_id', ARGV[1], 'id', ARGV[2], 'identityId', ARGV[3], 'authVersion', ARGV[4])
                    redis.call('PEXPIREAT', KEYS[1], ARGV[5])
                    return 1
                    """,
                    Long.class);

    // 旧缓存只先返回账号指针。数据库确认其属于当前租户后，第二次读取仍须匹配同一账号。
    private static final DefaultRedisScript<List> READ =
            new DefaultRedisScript<>(
                    """
                    local tenant = redis.call('HGET', KEYS[1], 'tenant_id')
                    if tenant and tenant ~= ARGV[1] then return {} end
                    local identity = redis.call('HGET', KEYS[1], 'identityId')
                    if not identity then return {} end
                    if not tenant and identity ~= ARGV[2] then return {identity} end
                    return redis.call('HMGET', KEYS[1], 'id', 'identityId', 'authVersion')
                    """,
                    List.class);
    private final StringRedisTemplate redis;

    public RedisAccessTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(
            String tokenHash, UUID tokenId, UUID identityId, long authVersion, Instant expiresAt) {
        Long saved =
                redis.execute(
                        SAVE,
                        List.of(PREFIX + tokenHash),
                        TenantScope.require(),
                        tokenId.toString(),
                        identityId.toString(),
                        Long.toString(authVersion),
                        Long.toString(expiresAt.toEpochMilli()));
        if (!Long.valueOf(1).equals(saved)) throw new IllegalStateException("令牌归属冲突");
    }

    /** 认证启动只读租户指针，旧格式的账号指针交由数据库确认。 */
    public String findTenant(String tokenHash, Function<UUID, String> legacyOwner) {
        TenantScope.requireUnbound();
        var owner =
                redis.<String, String>opsForHash()
                        .multiGet(PREFIX + tokenHash, List.of(TENANT, IDENTITY));
        if (owner.get(0) != null) return owner.get(0);
        return owner.get(1) == null ? null : legacyOwner.apply(UUID.fromString(owner.get(1)));
    }

    public Token find(String tokenHash, Predicate<UUID> ownsLegacyUser) {
        String tenant = TenantScope.require();
        var keys = List.of(PREFIX + tokenHash);
        List<?> values = redis.execute(READ, keys, tenant, "");
        if (values.size() == 1) {
            String identity = (String) values.getFirst();
            if (!ownsLegacyUser.test(UUID.fromString(identity))) return null;
            values = redis.execute(READ, keys, tenant, identity);
        }
        if (values.size() != 3) return null;
        return new Token(
                UUID.fromString((String) values.get(0)),
                UUID.fromString((String) values.get(1)),
                Long.parseLong((String) values.get(2)));
    }
}
