package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.metricplatform.identity.PlatformIdentityRepository.Token;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.persistence.redis.RedisAccessTokenStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

class RedisTenantTokenTest {
    @Test
    void verifiesOwnershipBeforeReturningOrOverwritingTokenData() {
        try (var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)) {
            redis.start();
            var connection =
                    new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            connection.afterPropertiesSet();
            try {
                var template = new StringRedisTemplate(connection);
                var store = new RedisAccessTokenStore(template);
                String tenant = "tn_00001", other = "tn_00002";
                UUID id = UUID.randomUUID(), user = UUID.randomUUID();
                var expiry = Instant.now().plusSeconds(300);
                var expected = new Token(id, user, 1);
                template.opsForHash()
                        .putAll(
                                "metric-platform:auth-token:legacy",
                                Map.of(
                                        "id",
                                        id.toString(),
                                        "identityId",
                                        user.toString(),
                                        "authVersion",
                                        "1"));

                assertThatThrownBy(() -> store.find("token", u -> true))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> store.save("token", id, user, 1, expiry))
                        .isInstanceOf(IllegalStateException.class);
                try (var scope = TenantScope.open(tenant)) {
                    store.save("token", id, user, 1, expiry);
                    assertThat(store.find("legacy", user::equals)).isEqualTo(expected);
                    assertThatThrownBy(() -> store.findTenant("token", u -> other))
                            .isInstanceOf(IllegalStateException.class);
                }
                assertThat(store.findTenant("token", u -> other)).isEqualTo(tenant);
                assertThat(template.getExpire("metric-platform:auth-token:token"))
                        .isBetween(1L, 300L);
                try (var scope = TenantScope.open(other)) {
                    assertThat(store.find("token", u -> true)).isNull();
                    assertThat(store.find("legacy", u -> false)).isNull();
                    assertThatThrownBy(
                                    () -> store.save("token", UUID.randomUUID(), user, 2, expiry))
                            .isInstanceOf(IllegalStateException.class);
                }
                try (var scope = TenantScope.open(tenant)) {
                    assertThat(store.find("token", u -> false)).isEqualTo(expected);
                    assertThat(
                                    store.find(
                                            "legacy",
                                            identity -> {
                                                template.opsForHash()
                                                        .put(
                                                                "metric-platform:auth-token:legacy",
                                                                "identityId",
                                                                UUID.randomUUID().toString());
                                                return true;
                                            }))
                            .isNull();
                    store.save("expired", id, user, 1, Instant.now().minusSeconds(1));
                    assertThat(store.find("expired", u -> true)).isNull();
                }
            } finally {
                connection.destroy();
            }
        }
    }
}
