package com.metricplatform.identity;

import java.time.Instant;
import java.util.UUID;

/** 认证流程所需的身份持久化端口，不暴露 MyBatis 实体或查询条件。 */
public interface PlatformIdentityRepository {
    /** 登录、锁定和令牌校验使用的账户快照。 */
    record User(
            UUID id,
            String username,
            String displayName,
            String passwordHash,
            String status,
            long authVersion,
            int failedLoginCount,
            Instant lockedUntil) {}

    /** 已确认有效的令牌归属。 */
    record Token(UUID id, UUID identityId, long authVersion) {}

    /** 首次启动时创建的管理员账户字段。 */
    record NewUser(
            UUID id,
            String username,
            String displayName,
            String passwordHash,
            String passwordAlgorithm,
            String status,
            long authVersion,
            Instant passwordChangedAt) {}

    User findByUsername(String username);

    User findById(UUID identityId);

    boolean usernameExists(String username);

    void createUser(NewUser user);

    void recordSuccessfulLogin(UUID identityId, Instant now);

    void recordFailedLogin(UUID identityId, int failedCount, Instant lockedUntil);

    void saveToken(
            String tokenHash,
            UUID tokenId,
            UUID identityId,
            long authVersion,
            Instant issuedAt,
            Instant expiresAt);

    Token findToken(String tokenHash, Instant now);

    void writeAudit(
            String actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String outcome,
            String detailJson);
}
