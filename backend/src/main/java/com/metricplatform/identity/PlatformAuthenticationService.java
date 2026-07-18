package com.metricplatform.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.metricplatform.identity.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.IdentityDtos.AuthTokenResponse;
import com.metricplatform.identity.IdentityDtos.IdentitySummary;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.entity.PlatformTenantEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.entity.PlatformUserWorkspaceEntity;
import com.metricplatform.persistence.entity.PlatformWorkspaceEntity;
import com.metricplatform.persistence.service.PlatformAccessTokenPersistenceService;
import com.metricplatform.persistence.service.PlatformTenantPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
import com.metricplatform.persistence.service.PlatformUserWorkspacePersistenceService;
import com.metricplatform.persistence.service.PlatformWorkspacePersistenceService;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.PlatformScopePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformAuthenticationService {
    private static final String AUDIENCE = "data-agent";
    private static final String CLIENT_ID = "data-agent";
    private static final String AUTHENTICATION_METHOD = "PASSWORD_TOKEN";

    private final PlatformIdentityProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTenantPersistenceService tenants;
    private final PlatformUserPersistenceService users;
    private final PlatformUserWorkspacePersistenceService userWorkspaces;
    private final PlatformWorkspacePersistenceService workspaces;
    private final PlatformAccessTokenPersistenceService tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public PlatformAuthenticationService(
            PlatformIdentityProperties properties,
            LoginRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            PlatformTenantPersistenceService tenants,
            PlatformUserPersistenceService users,
            PlatformUserWorkspacePersistenceService userWorkspaces,
            PlatformWorkspacePersistenceService workspaces,
            PlatformAccessTokenPersistenceService tokens,
            Clock clock) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.tenants = tenants;
        this.users = users;
        this.userWorkspaces = userWorkspaces;
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokenResponse login(AuthTokenRequest request, String remoteAddress) {
        if (!properties.isEnabled() || !AUDIENCE.equals(request.audience())) {
            throw invalidCredentials();
        }
        String tenantKey = normalize(request.tenantKey());
        String username = normalize(request.username());
        rateLimiter.checkAllowed(remoteAddress, tenantKey, username);

        PlatformTenantEntity tenant =
                tenants.getOne(
                        new LambdaQueryWrapper<PlatformTenantEntity>()
                                .eq(PlatformTenantEntity::getTenantKey, tenantKey));
        PlatformUserEntity user =
                tenant == null
                        ? null
                        : users.getOne(
                                new LambdaQueryWrapper<PlatformUserEntity>()
                                        .eq(PlatformUserEntity::getTenantId, tenant.getId())
                                        .eq(PlatformUserEntity::getUsername, username));

        Instant now = clock.instant();
        String storedHash =
                user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                        ? dummyPasswordHash
                        : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        boolean eligible =
                tenant != null
                        && "ACTIVE".equals(tenant.getStatus())
                        && user != null
                        && "ACTIVE".equals(user.getStatus())
                        && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now));
        if (!eligible || !passwordMatches || user == null) {
            rateLimiter.recordFailure(remoteAddress, tenantKey, username);
            recordFailedLogin(user, now);
            throw invalidCredentials();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.updateById(user);
        rateLimiter.recordSuccess(remoteAddress, tenantKey, username);

        String rawToken = newToken();
        PlatformAccessTokenEntity token = new PlatformAccessTokenEntity();
        token.setId(UUID.randomUUID());
        token.setTokenHash(hash(rawToken));
        token.setIdentityId(user.getId());
        token.setAudience(AUDIENCE);
        token.setAuthVersion(user.getAuthVersion());
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.getTokenTtl()));
        tokens.save(token);

        PlatformPrincipal principal = principal(user, tenant, token.getId());
        return new AuthTokenResponse(
                rawToken, "Bearer", properties.getTokenTtl().toSeconds(), summary(principal));
    }

    @Transactional(readOnly = true)
    public PlatformPrincipal authenticate(String rawToken) {
        if (!properties.isEnabled() || rawToken == null || rawToken.isBlank()) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        PlatformAccessTokenEntity token =
                tokens.getOne(
                        new LambdaQueryWrapper<PlatformAccessTokenEntity>()
                                .eq(PlatformAccessTokenEntity::getTokenHash, hash(rawToken))
                                .eq(PlatformAccessTokenEntity::getAudience, AUDIENCE)
                                .isNull(PlatformAccessTokenEntity::getRevokedAt));
        if (token == null || !token.getExpiresAt().isAfter(now)) throw invalidCredentials();

        PlatformUserEntity user = users.getById(token.getIdentityId());
        if (user == null
                || !"ACTIVE".equals(user.getStatus())
                || user.getAuthVersion() != token.getAuthVersion()) {
            throw invalidCredentials();
        }
        PlatformTenantEntity tenant = tenants.getById(user.getTenantId());
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) throw invalidCredentials();
        return principal(user, tenant, token.getId());
    }

    public IdentitySummary summary(PlatformPrincipal principal) {
        return new IdentitySummary(
                principal.identityId(),
                principal.subject(),
                principal.displayName(),
                principal.tenantId(),
                principal.workspaceIds(),
                principal.roles(),
                principal.scopes(),
                principal.authVersion());
    }

    @Transactional
    public void revokeAll(UUID identityId) {
        tokens.update(
                new LambdaUpdateWrapper<PlatformAccessTokenEntity>()
                        .eq(PlatformAccessTokenEntity::getIdentityId, identityId)
                        .isNull(PlatformAccessTokenEntity::getRevokedAt)
                        .set(PlatformAccessTokenEntity::getRevokedAt, clock.instant()));
    }

    private void recordFailedLogin(PlatformUserEntity user, Instant now) {
        if (user == null) return;
        int nextCount = user.getFailedLoginCount() + 1;
        if (nextCount >= properties.getLockThreshold()) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(now.plus(properties.getLockDuration()));
        } else {
            user.setFailedLoginCount(nextCount);
        }
        users.updateById(user);
    }

    private PlatformPrincipal principal(
            PlatformUserEntity user, PlatformTenantEntity tenant, UUID credentialId) {
        Set<String> roles = Set.of(user.getRoleCode());
        return new PlatformPrincipal(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                tenant.getId(),
                workspaceIds(user),
                roles,
                PlatformScopePolicy.scopesFor(roles),
                CLIENT_ID,
                AUTHENTICATION_METHOD,
                credentialId,
                user.getAuthVersion());
    }

    private Set<UUID> workspaceIds(PlatformUserEntity user) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (PlatformUserWorkspaceEntity membership :
                userWorkspaces.list(
                        new LambdaQueryWrapper<PlatformUserWorkspaceEntity>()
                                .eq(PlatformUserWorkspaceEntity::getTenantId, user.getTenantId())
                                .eq(PlatformUserWorkspaceEntity::getIdentityId, user.getId()))) {
            PlatformWorkspaceEntity workspace = workspaces.getById(membership.getWorkspaceId());
            if (workspace != null
                    && user.getTenantId().equals(workspace.getTenantId())
                    && "ACTIVE".equals(workspace.getStatus())) {
                result.add(workspace.getId());
            }
        }
        return Set.copyOf(result);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid credentials");
    }
}
