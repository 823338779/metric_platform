package com.metricplatform.persistence.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.redis.RedisAccessTokenStore;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import com.metricplatform.persistence.service.PlatformAccessTokenPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 使用 IService 保存身份、令牌和审计记录，Redis 可用时保存短期令牌。 */
@Repository
@RequiredArgsConstructor
public class PlatformIdentityStore implements PlatformIdentityRepository {
    /** 在账号创建事务内分配租户并绑定管理者。 */
    private static final String CREATE_TENANT =
            """
            insert into platform_tenant(tenant_id,manager_id)
            values ('tn_' || lpad(nextval('tenant_number')::text,5,'0'),?) returning tenant_id
            """;

    private final JdbcTemplate jdbc;
    private final PlatformUserPersistenceService users;

    public String createTenant(UUID managerId) {
        requireUnbound();
        return jdbc.queryForObject(CREATE_TENANT, String.class, managerId);
    }

    private final PlatformAccessTokenPersistenceService tokens;
    private final AuditEventPersistenceService events;
    private final ObjectProvider<RedisAccessTokenStore> redisTokens;

    private static final String MEMBERSHIP =
            """
            select u.tenant_id, t.manager_id=u.id from platform_user u
            join platform_tenant t using(tenant_id) where u.tenant_id=? and u.id=? and not u.is_deleted
            """;
    private static final String LOGIN_TENANT =
            "select tenant_id from platform_user where username=? and not is_deleted";
    private static final String LEGACY_TOKEN_USER_TENANT =
            "select tenant_id from platform_user where id=? and not is_deleted";
    private static final String TOKEN_TENANT =
            """
            select tenant_id from platform_access_token
            where token_hash=? and not is_deleted and revoked_at is null and expires_at>?
            """;

    public Membership membership(UUID accountId) {
        return jdbc.queryForObject(
                MEMBERSHIP,
                (row, index) -> new Membership(row.getString(1), row.getBoolean(2)),
                TenantScope.require(),
                accountId);
    }

    public String findLoginTenant(String username) {
        requireUnbound();
        return jdbc.query(LOGIN_TENANT, row -> row.next() ? row.getString(1) : null, username);
    }

    public User findByUsername(String username) {
        return user(
                users.getOne(
                        new LambdaQueryWrapper<PlatformUserEntity>()
                                .eq(PlatformUserEntity::getUsername, username)));
    }

    public User findById(UUID identityId) {
        return user(users.getById(identityId));
    }

    public boolean usernameExists(String username) {
        return users.count(
                        new LambdaQueryWrapper<PlatformUserEntity>()
                                .eq(PlatformUserEntity::getUsername, username))
                > 0;
    }

    public void createUser(NewUser value) {
        TenantScope.require(value.tenantId());
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(value.id());
        user.setUsername(value.username());
        user.setDisplayName(value.displayName());
        user.setPasswordHash(value.passwordHash());
        user.setPasswordAlgorithm(value.passwordAlgorithm());
        user.setStatus(value.status());
        user.setAuthVersion(value.authVersion());
        user.setPasswordChangedAt(value.passwordChangedAt());
        users.save(user);
    }

    public void recordSuccessfulLogin(UUID identityId, Instant now) {
        // 显式写入 null，清除此前的账户锁定时间。
        users.lambdaUpdate()
                .eq(PlatformUserEntity::getId, identityId)
                .set(PlatformUserEntity::getFailedLoginCount, 0)
                .set(PlatformUserEntity::getLockedUntil, null)
                .set(PlatformUserEntity::getLastLoginAt, now)
                .update();
    }

    public void recordFailedLogin(UUID identityId, int failedCount, Instant lockedUntil) {
        users.lambdaUpdate()
                .eq(PlatformUserEntity::getId, identityId)
                .set(PlatformUserEntity::getFailedLoginCount, failedCount)
                .set(PlatformUserEntity::getLockedUntil, lockedUntil)
                .update();
    }

    public void saveToken(
            String tokenHash,
            UUID tokenId,
            UUID identityId,
            long authVersion,
            Instant issuedAt,
            Instant expiresAt) {
        RedisAccessTokenStore redis = redisTokens.getIfAvailable();
        if (redis != null) {
            redis.save(tokenHash, tokenId, identityId, authVersion, expiresAt);
            return;
        }
        PlatformAccessTokenEntity token = new PlatformAccessTokenEntity();
        token.setId(tokenId);
        token.setTokenHash(tokenHash);
        token.setIdentityId(identityId);
        token.setAuthVersion(authVersion);
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(expiresAt);
        tokens.save(token);
    }

    public String findTokenTenant(String tokenHash, Instant now) {
        requireUnbound();
        RedisAccessTokenStore redis = redisTokens.getIfAvailable();
        if (redis != null) {
            return redis.findTenant(tokenHash, this::legacyTokenTenant);
        }
        return jdbc.query(
                TOKEN_TENANT,
                row -> row.next() ? row.getString(1) : null,
                tokenHash,
                Timestamp.from(now));
    }

    private String legacyTokenTenant(UUID identityId) {
        // 旧 Redis 令牌由缓存中的账号定位；其有效期和账号版本仍按正常流程校验。
        return jdbc.query(
                LEGACY_TOKEN_USER_TENANT, row -> row.next() ? row.getString(1) : null, identityId);
    }

    public Token findToken(String tokenHash, Instant now) {
        RedisAccessTokenStore redis = redisTokens.getIfAvailable();
        if (redis != null) {
            String tenant = TenantScope.require();
            var token = redis.find(tokenHash);
            if (token == null) return null;
            // 旧令牌没有签发租户，必须在当前租户内找到同一账号。
            boolean owned =
                    token.tenantId() != null
                            ? tenant.equals(token.tenantId())
                            : users.lambdaQuery()
                                    .eq(PlatformUserEntity::getId, token.identityId())
                                    .exists();
            return owned ? new Token(token.id(), token.identityId(), token.authVersion()) : null;
        }
        PlatformAccessTokenEntity token =
                tokens.getOne(
                        new LambdaQueryWrapper<PlatformAccessTokenEntity>()
                                .eq(PlatformAccessTokenEntity::getTokenHash, tokenHash)
                                .isNull(PlatformAccessTokenEntity::getRevokedAt));
        return token == null || !token.getExpiresAt().isAfter(now)
                ? null
                : new Token(token.getId(), token.getIdentityId(), token.getAuthVersion());
    }

    public void writeAudit(
            String actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String outcome,
            String detailJson) {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(UUID.randomUUID());
        event.setActorId(actorId);
        event.setEffectiveIdentityId(resourceId);
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setOutcome(outcome);
        event.setDetailJson(detailJson);
        events.save(event);
    }

    private static User user(PlatformUserEntity row) {
        return row == null
                ? null
                : new User(
                        row.getId(),
                        row.getUsername(),
                        row.getDisplayName(),
                        row.getPasswordHash(),
                        row.getStatus(),
                        row.getAuthVersion(),
                        row.getFailedLoginCount(),
                        row.getLockedUntil());
    }

    /** 全局登录发现和租户分配仅用于尚未绑定业务租户的入口。 */
    private static void requireUnbound() {
        if (TenantScope.current() != null) throw new IllegalStateException("当前已绑定租户");
    }
}
