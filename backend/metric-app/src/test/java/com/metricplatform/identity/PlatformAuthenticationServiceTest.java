package com.metricplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.identity.auth.LoginRateLimiter;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.identity.config.PlatformIdentityProperties;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PlatformAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Mock LoginRateLimiter rateLimiter;
    @Mock PlatformIdentityRepository identities;

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
                        identities,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loginReturnsOnlyAccountIdentityAndTenHourToken() {
        UUID userId = UUID.randomUUID();
        PlatformIdentityRepository.User user =
                new PlatformIdentityRepository.User(
                        userId,
                        "admin",
                        "Platform Administrator",
                        passwordEncoder.encode("admin"),
                        "ACTIVE",
                        2,
                        0,
                        null);
        when(identities.findLoginTenant("admin")).thenReturn("tn_00001");
        when(identities.findByUsername("admin"))
                .thenAnswer(
                        call -> {
                            assertThat(TenantScope.require()).isEqualTo("tn_00001");
                            return user;
                        });
        when(identities.membership(userId))
                .thenReturn(new PlatformIdentityRepository.Membership("tn_00001", true));

        var response = service.login(new AuthTokenRequest("admin", "admin"), "127.0.0.1");

        assertThat(response.expiresIn()).isEqualTo(Duration.ofHours(10).toSeconds());
        assertThat(response.identity().identityId()).isEqualTo(userId);
        assertThat(response.identity().subject()).isEqualTo("admin");
        assertThat(response.identity().displayName()).isEqualTo("Platform Administrator");
        assertThat(response.identity().authVersion()).isEqualTo(2);
        assertThat(
                        Arrays.stream(response.identity().getClass().getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly(
                        "identityId", "subject", "displayName", "authVersion", "tenant_id", "role");

        ArgumentCaptor<String> tokenHash = ArgumentCaptor.forClass(String.class);
        verify(identities)
                .saveToken(
                        tokenHash.capture(),
                        any(UUID.class),
                        eq(userId),
                        eq(2L),
                        eq(NOW),
                        eq(NOW.plus(Duration.ofHours(10))));
        assertThat(tokenHash.getValue()).hasSize(64);
        assertThat(TenantScope.current()).isNull();
    }

    @Test
    void unknownUsernameDoesNotCreateATenantScope() {
        assertThatThrownBy(() -> service.login(new AuthTokenRequest("missing", "bad"), "127.0.0.1"))
                .isInstanceOf(BadCredentialsException.class);
        verify(identities, never()).findByUsername(any());
        verify(rateLimiter).recordFailure("127.0.0.1", "missing");
        assertThat(TenantScope.current()).isNull();
    }

    @Test
    void boundRequestCannotDiscoverAnotherTokensTenant() {
        try (var scope = TenantScope.open("tn_00001")) {
            assertThatThrownBy(() -> service.authenticate("other-tenant-token"))
                    .isInstanceOf(BadCredentialsException.class);
            verify(identities, never()).findTokenTenant(any(), any());
            assertThat(TenantScope.require()).isEqualTo("tn_00001");
        }
    }
}
