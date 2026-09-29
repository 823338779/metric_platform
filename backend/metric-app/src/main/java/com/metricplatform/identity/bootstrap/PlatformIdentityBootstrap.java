package com.metricplatform.identity.bootstrap;

import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.identity.audit.PlatformIdentityAuditWriter;
import com.metricplatform.identity.config.PlatformIdentityProperties;
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
    private static final String PASSWORD_ALGORITHM = "bcrypt", ACTIVE = "ACTIVE";
    private final PlatformIdentityProperties properties;
    private final PlatformIdentityRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PlatformIdentityAuditWriter audit;
    private final Clock clock;

    public PlatformIdentityBootstrap(
            PlatformIdentityProperties properties,
            PlatformIdentityRepository users,
            PasswordEncoder passwordEncoder,
            PlatformIdentityAuditWriter audit,
            Clock clock) {
        this.properties = properties;
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

        String username = normalize(bootstrap.getUsername());
        if (users.usernameExists(username)) return;

        UUID identityId = UUID.randomUUID();
        users.createUser(
                new PlatformIdentityRepository.NewUser(
                        identityId,
                        username,
                        bootstrap.getDisplayName().trim(),
                        passwordEncoder.encode(bootstrap.getPassword()),
                        PASSWORD_ALGORITHM,
                        ACTIVE,
                        1,
                        clock.instant()));
        audit.write(
                null,
                "PLATFORM_IDENTITY_BOOTSTRAPPED",
                "platform_user",
                identityId,
                "SUCCEEDED",
                Map.of("username", username));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
