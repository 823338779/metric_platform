package com.metricplatform.identity.auth;

import com.metricplatform.identity.config.PlatformIdentityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class LoginRateLimiter {
    private static final int MAX_KEYS = 10_000;

    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final PlatformIdentityProperties properties;
    private final Clock clock;

    public LoginRateLimiter(PlatformIdentityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkAllowed(String remoteAddress, String username) {
        Instant now = clock.instant();
        String key = key(remoteAddress, username);
        AttemptWindow window = attempts.get(key);
        if (window != null && expired(window, now)) {
            attempts.remove(key, window);
            window = null;
        }
        if (window != null && window.failures() >= properties.getRateLimit()) {
            throw new LoginRateLimitException();
        }
        if (window == null && attempts.size() >= MAX_KEYS) {
            attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            if (attempts.size() >= MAX_KEYS) throw new LoginRateLimitException();
        }
    }

    public void recordFailure(String remoteAddress, String username) {
        Instant now = clock.instant();
        attempts.compute(
                key(remoteAddress, username),
                (ignored, current) ->
                        current == null || expired(current, now)
                                ? new AttemptWindow(now, 1)
                                : new AttemptWindow(current.startedAt(), current.failures() + 1));
    }

    public void recordSuccess(String remoteAddress, String username) {
        attempts.remove(key(remoteAddress, username));
    }

    private boolean expired(AttemptWindow window, Instant now) {
        return !window.startedAt().plus(properties.getRateWindow()).isAfter(now);
    }

    private static String key(String remoteAddress, String username) {
        String material = normalized(remoteAddress) + "\n" + normalized(username);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record AttemptWindow(Instant startedAt, int failures) {}
}
