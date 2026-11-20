package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.redis.RedisAccessTokenStore;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 验证身份端口通过 MyBatis Plus 落库并返回不含持久化实体的账户快照。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlatformIdentityStoreTest {
    @Autowired PlatformIdentityRepository identities;
    @Autowired AuditEventPersistenceService events;
    @MockitoBean RedisAccessTokenStore redis;

    @Test
    void missingTenantPreventsAccountReadsAndAuditWrites() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> identities.findById(id)).hasStackTraceContaining("缺少租户上下文");
        assertThatThrownBy(() -> identities.membership(id)).hasStackTraceContaining("缺少租户上下文");
        assertThatThrownBy(
                        () ->
                                identities.writeAudit(
                                        "bootstrap",
                                        "LOGIN",
                                        "platform_user",
                                        id,
                                        "SUCCEEDED",
                                        "{}"))
                .hasStackTraceContaining("缺少租户上下文");
    }

    @Test
    void createsAndUpdatesUserAndWritesAudit() {
        UUID id = UUID.randomUUID();
        String username = "user-" + id;
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        String tenant = identities.createTenant(id);
        try (var scope = TenantScope.open(tenant)) {
            identities.createUser(
                    new PlatformIdentityRepository.NewUser(
                            id, username, "Test User", "hash", "bcrypt", "ACTIVE", 1, now, tenant));
            assertThat(identities.usernameExists(username)).isTrue();
            assertThat(identities.findByUsername(username).id()).isEqualTo(id);
            assertThat(identities.membership(id))
                    .isEqualTo(new PlatformIdentityRepository.Membership(tenant, true));
            identities.recordFailedLogin(id, 2, now.plusSeconds(60));
            assertThat(identities.findById(id).failedLoginCount()).isEqualTo(2);
            assertThat(identities.findById(id).lockedUntil()).isEqualTo(now.plusSeconds(60));

            identities.recordSuccessfulLogin(id, now);
            assertThat(identities.findById(id).failedLoginCount()).isZero();
            assertThat(identities.findById(id).lockedUntil()).isNull();
            identities.writeAudit("bootstrap", "LOGIN", "platform_user", id, "SUCCEEDED", "{}");
        }
        assertThat(identities.findLoginTenant(username)).isEqualTo(tenant);

        // 同一个主键在其他租户中既不能读取，也不能修改登录失败次数。
        UUID otherManager = UUID.randomUUID();
        String otherTenant = identities.createTenant(otherManager);
        try (var scope = TenantScope.open(otherTenant)) {
            identities.createUser(
                    new PlatformIdentityRepository.NewUser(
                            otherManager,
                            "user-" + otherManager,
                            "Other",
                            "hash",
                            "bcrypt",
                            "ACTIVE",
                            1,
                            now,
                            otherTenant));
            assertThat(identities.findByUsername(username)).isNull();
            assertThat(identities.findById(id)).isNull();
            assertThatThrownBy(() -> identities.membership(id))
                    .isInstanceOf(EmptyResultDataAccessException.class);
            assertThat(identities.usernameExists(username)).isFalse();
            identities.recordFailedLogin(id, 7, null);
            assertThatThrownBy(
                            () ->
                                    identities.saveToken(
                                            "fixture-token-hash",
                                            UUID.randomUUID(),
                                            id,
                                            1,
                                            now,
                                            now.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("令牌账号不属于当前租户");
            assertThatThrownBy(() -> identities.findLoginTenant(username))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> identities.findTokenTenant("fixture-token-hash", now))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(redis);
        }
        try (var scope = TenantScope.open(tenant)) {
            assertThat(identities.findById(id).failedLoginCount()).isZero();
            assertThat(events.lambdaQuery().eq(AuditEventEntity::getResourceId, id).count())
                    .isEqualTo(1);
        }
    }
}
