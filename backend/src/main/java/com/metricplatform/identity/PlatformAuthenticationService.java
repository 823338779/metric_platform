package com.metricplatform.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.identity.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.IdentityDtos.AuthTokenResponse;
import com.metricplatform.identity.IdentityDtos.IdentitySummary;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.service.PlatformAccessTokenPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformAuthenticationService {
    private final PlatformIdentityProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final PlatformUserPersistenceService users;
    private final PlatformAccessTokenPersistenceService tokens;
    private final RedisAccessTokenStore redisTokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public PlatformAuthenticationService(
            PlatformIdentityProperties properties,
            LoginRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            PlatformUserPersistenceService users,
            PlatformAccessTokenPersistenceService tokens,
            Clock clock) {
        this(properties, rateLimiter, passwordEncoder, users, tokens, null, clock);
    }

    @Autowired
    public PlatformAuthenticationService(
            PlatformIdentityProperties properties,
            LoginRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            PlatformUserPersistenceService users,
            PlatformAccessTokenPersistenceService tokens,
            RedisAccessTokenStore redisTokens,
            Clock clock) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.users = users;
        this.tokens = tokens;
        this.redisTokens = redisTokens;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokenResponse login(AuthTokenRequest request, String remoteAddress) {
        if (!properties.isEnabled()) throw invalidCredentials();
        String username = normalize(request.username());
        rateLimiter.checkAllowed(remoteAddress, username);

        PlatformUserEntity user =
                users.getOne(
                        new LambdaQueryWrapper<PlatformUserEntity>()
                                .eq(PlatformUserEntity::getUsername, username));
        Instant now = clock.instant();
        String storedHash =
                user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                        ? dummyPasswordHash
                        : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        boolean eligible =
                user != null
                        && "ACTIVE".equals(user.getStatus())
                        && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now));
        if (!eligible || !passwordMatches) {
            rateLimiter.recordFailure(remoteAddress, username);
            recordFailedLogin(user, now);
            throw invalidCredentials();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.updateById(user);
        rateLimiter.recordSuccess(remoteAddress, username);

        String rawToken = newToken();
        UUID tokenId = UUID.randomUUID();
        Instant expiresAt = now.plus(properties.getTokenTtl());
        if (redisTokens != null) {
            redisTokens.save(hash(rawToken), tokenId, user.getId(), user.getAuthVersion(), expiresAt);
        } else {
            PlatformAccessTokenEntity token = new PlatformAccessTokenEntity();
            token.setId(tokenId);
            token.setTokenHash(hash(rawToken));
            token.setIdentityId(user.getId());
            token.setAuthVersion(user.getAuthVersion());
            token.setIssuedAt(now);
            token.setExpiresAt(expiresAt);
            tokens.save(token);
        }

        PlatformPrincipal principal = principal(user, tokenId);
        return new AuthTokenResponse(
                rawToken, "Bearer", properties.getTokenTtl().toSeconds(), summary(principal));
    }

    @Transactional(readOnly = true)
    public PlatformPrincipal authenticate(String rawToken) {
        if (!properties.isEnabled() || rawToken == null || rawToken.isBlank()) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        UUID credentialId;
        UUID identityId;
        long authVersion;
        if (redisTokens != null) {
            var token = redisTokens.find(hash(rawToken));
            if (token == null) throw invalidCredentials();
            credentialId = token.id();
            identityId = token.identityId();
            authVersion = token.authVersion();
        } else {
            PlatformAccessTokenEntity token =
                    tokens.getOne(
                            new LambdaQueryWrapper<PlatformAccessTokenEntity>()
                                    .eq(PlatformAccessTokenEntity::getTokenHash, hash(rawToken))
                                    .isNull(PlatformAccessTokenEntity::getRevokedAt));
            if (token == null || !token.getExpiresAt().isAfter(now)) throw invalidCredentials();
            credentialId = token.getId();
            identityId = token.getIdentityId();
            authVersion = token.getAuthVersion();
        }

        PlatformUserEntity user = users.getById(identityId);
        if (user == null
                || !"ACTIVE".equals(user.getStatus())
                || user.getAuthVersion() != authVersion) {
            throw invalidCredentials();
        }
        return principal(user, credentialId);
    }

    public IdentitySummary summary(PlatformPrincipal principal) {
        return new IdentitySummary(
                principal.identityId(),
                principal.subject(),
                principal.displayName(),
                principal.authVersion());
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

    private static PlatformPrincipal principal(PlatformUserEntity user, UUID credentialId) {
        return new PlatformPrincipal(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                credentialId,
                user.getAuthVersion());
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
