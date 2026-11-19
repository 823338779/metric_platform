package com.metricplatform.identity.api;

import com.metricplatform.security.PlatformPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class IdentityDtos {
    private IdentityDtos() {}

    public record AuthTokenRequest(
            @NotBlank String username, @NotBlank @Size(max = 1024) String password) {}

    public record IdentitySummary(
            UUID identityId,
            String subject,
            String displayName,
            long authVersion,
            /** 登录账号所属租户。 */
            String tenant_id,
            /** 当前账号在该租户内的角色。 */
            PlatformPrincipal.Role role) {}

    public record AuthTokenResponse(
            String accessToken, String tokenType, long expiresIn, IdentitySummary identity) {}
}
