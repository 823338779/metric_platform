package com.metricplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.metricplatform.identity.IdentityDtos.AuthTokenRequest;
import com.metricplatform.persistence.entity.PlatformAccessTokenEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.service.PlatformAccessTokenPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PlatformAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Mock LoginRateLimiter rateLimiter;
    @Mock PlatformUserPersistenceService users;
    @Mock PlatformAccessTokenPersistenceService tokens;

    private PlatformIdentityProperties properties;
    private BCryptPasswordEncoder passwordEncoder;
    private PlatformAuthenticationService service;

    @BeforeEach
    void setUp() {
        properties = new PlatformIdentityProperties();
        passwordEncoder = new BCryptPasswordEncoder(4);
        service =
                new PlatformAuthenticationService(
                        properties,
                        rateLimiter,
                        passwordEncoder,
                        users,
                        tokens,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loginReturnsOnlyAccountIdentityAndTenHourToken() {
        UUID userId = UUID.randomUUID();
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        user.setUsername("admin");
        user.setDisplayName("Platform Administrator");
        user.setPasswordHash(passwordEncoder.encode("admin"));
        user.setStatus("ACTIVE");
        user.setAuthVersion(2);
        when(users.getOne(any())).thenReturn(user);

        var response = service.login(new AuthTokenRequest("admin", "admin"), "127.0.0.1");

        assertThat(response.expiresIn()).isEqualTo(Duration.ofHours(10).toSeconds());
        assertThat(response.identity().identityId()).isEqualTo(userId);
        assertThat(response.identity().subject()).isEqualTo("admin");
        assertThat(response.identity().displayName()).isEqualTo("Platform Administrator");
        assertThat(response.identity().authVersion()).isEqualTo(2);
        assertThat(
                        Arrays.stream(response.identity().getClass().getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("identityId", "subject", "displayName", "authVersion");

        ArgumentCaptor<PlatformAccessTokenEntity> savedToken =
                ArgumentCaptor.forClass(PlatformAccessTokenEntity.class);
        verify(tokens).save(savedToken.capture());
        assertThat(savedToken.getValue().getIdentityId()).isEqualTo(userId);
        assertThat(savedToken.getValue().getAuthVersion()).isEqualTo(2);
        assertThat(savedToken.getValue().getIssuedAt()).isEqualTo(NOW);
        assertThat(savedToken.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(10)));
        assertThat(savedToken.getValue().getTokenHash()).hasSize(64);
    }
}
