package com.metricplatform.identity.auth;

import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenResponse;
import com.metricplatform.identity.api.IdentityDtos.IdentitySummary;
import com.metricplatform.identity.config.PlatformIdentityProperties;
import com.metricplatform.security.PlatformPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class PlatformAuthenticationService {
    private static final String UNKNOWN_ACCOUNT_FAILURE = "登录失败：无法确定账号所属租户";
    private static final String ACTIVE = "ACTIVE", BEARER = "Bearer";
    private final PlatformIdentityProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final PlatformIdentityRepository identities;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public PlatformAuthenticationService(
            PlatformIdentityProperties properties,
            LoginRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            PlatformIdentityRepository identities,
            Clock clock) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.identities = identities;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokenResponse login(AuthTokenRequest request, String remoteAddress) {
        if (!properties.isEnabled()) throw invalidCredentials();
        String username = normalize(request.username());
        rateLimiter.checkAllowed(remoteAddress, username);

        // 只有尚未认证的请求可以根据登录名发现租户；已有会话只能在本租户核验账号。
        String tenant = TenantScope.current();
        if (tenant == null) tenant = identities.findLoginTenant(username);
        if (tenant == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            rateLimiter.recordFailure(remoteAddress, username);
            log.warn(UNKNOWN_ACCOUNT_FAILURE);
            throw invalidCredentials();
        }
        try (var scope = TenantScope.open(tenant)) {
            return loginInTenant(username, request.password(), remoteAddress);
        }
    }

    private AuthTokenResponse loginInTenant(
            String username, String password, String remoteAddress) {
        var user = identities.findByUsername(username);
        Instant now = clock.instant();
        String storedHash =
                user == null || user.passwordHash() == null || user.passwordHash().isBlank()
                        ? dummyPasswordHash
                        : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);
        boolean eligible =
                user != null
                        && ACTIVE.equals(user.status())
                        && (user.lockedUntil() == null || !user.lockedUntil().isAfter(now));
        if (!eligible || !passwordMatches) {
            rateLimiter.recordFailure(remoteAddress, username);
            recordFailedLogin(user, now);
            throw invalidCredentials();
        }

        identities.recordSuccessfulLogin(user.id(), now);
        rateLimiter.recordSuccess(remoteAddress, username);

        String rawToken = newToken();
        UUID tokenId = UUID.randomUUID();
        Instant expiresAt = now.plus(properties.getTokenTtl());
        identities.saveToken(
                hash(rawToken), tokenId, user.id(), user.authVersion(), now, expiresAt);

        PlatformPrincipal principal = principal(user, tokenId);
        return new AuthTokenResponse(
                rawToken, BEARER, properties.getTokenTtl().toSeconds(), summary(principal));
    }

    @Transactional(readOnly = true)
    public PlatformPrincipal authenticate(String rawToken) {
        if (!properties.isEnabled() || rawToken == null || rawToken.isBlank()) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        String tokenHash = hash(rawToken);
        String tenant = TenantScope.current();
        if (tenant == null) tenant = identities.findTokenTenant(tokenHash, now);
        if (tenant == null) throw invalidCredentials();
        try (var scope = TenantScope.open(tenant)) {
            return authenticateInTenant(tokenHash, now);
        }
    }

    private PlatformPrincipal authenticateInTenant(String tokenHash, Instant now) {
        var token = identities.findToken(tokenHash, now);
        if (token == null) throw invalidCredentials();

        PlatformIdentityRepository.User user = identities.findById(token.identityId());
        if (user == null
                || !ACTIVE.equals(user.status())
                || user.authVersion() != token.authVersion()) {
            throw invalidCredentials();
        }
        return principal(user, token.id());
    }

    public IdentitySummary summary(PlatformPrincipal principal) {
        return new IdentitySummary(
                principal.identityId(),
                principal.subject(),
                principal.displayName(),
                principal.authVersion(),
                principal.tenantId(),
                principal.role());
    }

    private void recordFailedLogin(PlatformIdentityRepository.User user, Instant now) {
        if (user == null) return;
        int nextCount = user.failedLoginCount() + 1;
        if (nextCount >= properties.getLockThreshold()) {
            identities.recordFailedLogin(user.id(), 0, now.plus(properties.getLockDuration()));
        } else {
            identities.recordFailedLogin(user.id(), nextCount, user.lockedUntil());
        }
    }

    private PlatformPrincipal principal(PlatformIdentityRepository.User user, UUID credentialId) {
        // 始终读取当前归属，旧令牌不能延续迁移前的权限。
        var membership = identities.membership(user.id());
        return new PlatformPrincipal(
                user.id(),
                user.username(),
                user.displayName(),
                credentialId,
                user.authVersion(),
                membership.tenantId(),
                membership.manager()
                        ? PlatformPrincipal.Role.TENANT_MANAGER
                        : PlatformPrincipal.Role.READ_ONLY);
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
