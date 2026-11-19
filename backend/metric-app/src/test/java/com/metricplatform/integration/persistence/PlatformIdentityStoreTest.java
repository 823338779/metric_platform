package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.identity.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 验证身份端口通过 MyBatis Plus 落库并返回不含持久化实体的账户快照。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlatformIdentityStoreTest {
    @Autowired PlatformIdentityRepository identities;
    @Autowired JdbcTemplate jdbc;

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
            identities.recordFailedLogin(id, 7, null);
        }
        try (var scope = TenantScope.open(tenant)) {
            assertThat(identities.findById(id).failedLoginCount()).isZero();
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from audit_event where resource_id = ? and"
                                        + " tenant_id = ?",
                                Integer.class,
                                id,
                                tenant))
                .isEqualTo(1);
    }
}
