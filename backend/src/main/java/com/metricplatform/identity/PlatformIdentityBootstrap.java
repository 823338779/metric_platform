package com.metricplatform.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.persistence.entity.PlatformTenantEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.service.PlatformTenantPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PlatformIdentityBootstrap implements ApplicationRunner {
    private final PlatformIdentityProperties properties;
    private final PlatformTenantPersistenceService tenants;
    private final PlatformUserPersistenceService users;
    private final PasswordEncoder passwordEncoder;
    private final PlatformIdentityAuditWriter audit;
    private final Clock clock;

    public PlatformIdentityBootstrap(
            PlatformIdentityProperties properties,
            PlatformTenantPersistenceService tenants,
            PlatformUserPersistenceService users,
            PasswordEncoder passwordEncoder,
            PlatformIdentityAuditWriter audit,
            Clock clock) {
        this.properties = properties;
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        PlatformIdentityProperties.Bootstrap bootstrap = properties.getBootstrap();
        if (!bootstrap.isEnabled()) return;
        if (bootstrap.getUsername() == null
                || bootstrap.getUsername().isBlank()
                || bootstrap.getPassword() == null
                || bootstrap.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "Metric Platform bootstrap username and password are required");
        }
        long administrators =
                users.count(
                        new LambdaQueryWrapper<PlatformUserEntity>()
                                .eq(PlatformUserEntity::getRoleCode, "PLATFORM_ADMIN")
                                .eq(PlatformUserEntity::getStatus, "ACTIVE"));
        if (administrators > 0) return;

        String tenantKey = normalize(bootstrap.getTenantKey());
        PlatformTenantEntity tenant =
                tenants.getOne(
                        new LambdaQueryWrapper<PlatformTenantEntity>()
                                .eq(PlatformTenantEntity::getTenantKey, tenantKey));
        if (tenant == null) {
            tenant = new PlatformTenantEntity();
            tenant.setId(UUID.randomUUID());
            tenant.setTenantKey(tenantKey);
            tenant.setDisplayName(tenantKey);
            tenant.setStatus("ACTIVE");
            tenants.save(tenant);
        }

        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenant.getId());
        user.setUsername(normalize(bootstrap.getUsername()));
        user.setDisplayName(bootstrap.getDisplayName().trim());
        user.setPasswordHash(passwordEncoder.encode(bootstrap.getPassword()));
        user.setPasswordAlgorithm("bcrypt");
        user.setRoleCode("PLATFORM_ADMIN");
        user.setStatus("ACTIVE");
        user.setAuthVersion(1);
        user.setPasswordChangedAt(clock.instant());
        users.save(user);
        audit.write(
                null,
                "PLATFORM_IDENTITY_BOOTSTRAPPED",
                "platform_user",
                user.getId(),
                "SUCCEEDED",
                Map.of("tenantId", tenant.getId(), "role", "PLATFORM_ADMIN"));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
