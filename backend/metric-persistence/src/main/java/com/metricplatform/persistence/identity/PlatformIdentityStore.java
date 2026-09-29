package com.metricplatform.persistence.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.redis.RedisAccessTokenStore;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import com.metricplatform.persistence.service.PlatformAccessTokenPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/** 使用 IService 保存身份、令牌和审计记录，Redis 可用时保存短期令牌。 */
@Repository
@RequiredArgsConstructor
public class PlatformIdentityStore implements PlatformIdentityRepository {
    private final PlatformUserPersistenceService users;
    private final PlatformAccessTokenPersistenceService tokens;
    private final AuditEventPersistenceService events;
    private final ObjectProvider<RedisAccessTokenStore> redisTokens;

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

    public Token findToken(String tokenHash, Instant now) {
        RedisAccessTokenStore redis = redisTokens.getIfAvailable();
        if (redis != null) {
            RedisAccessTokenStore.TokenData token = redis.find(tokenHash);
            return token == null
                    ? null
                    : new Token(token.id(), token.identityId(), token.authVersion());
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
}
