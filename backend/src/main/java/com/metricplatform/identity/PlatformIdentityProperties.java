package com.metricplatform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties("metric-platform.identity-auth")
public class PlatformIdentityProperties {
    private boolean enabled = true;
    @NotNull private Duration tokenTtl = Duration.ofMinutes(10);

    @Min(1)
    @Max(20)
    private int lockThreshold = 5;

    @NotNull private Duration lockDuration = Duration.ofMinutes(15);

    @Min(1)
    @Max(100)
    private int rateLimit = 10;

    @NotNull private Duration rateWindow = Duration.ofMinutes(1);
    @Valid private Bootstrap bootstrap = new Bootstrap();

    @AssertTrue(message = "Metric Platform access tokens must expire after exactly 10 minutes")
    public boolean isSupportedTokenTtl() {
        return Duration.ofMinutes(10).equals(tokenTtl);
    }

    @Getter
    @Setter
    public static class Bootstrap {
        private boolean enabled;
        private String tenantKey = "default";
        private String username = "";
        private String displayName = "Platform Administrator";
        private String password = "";
    }
}
