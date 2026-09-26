package com.metricplatform.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class IdentityDtos {
    private IdentityDtos() {}

    public record AuthTokenRequest(
            @NotBlank String username, @NotBlank @Size(max = 1024) String password) {}

    public record IdentitySummary(
            UUID identityId, String subject, String displayName, long authVersion) {}

    public record AuthTokenResponse(
            String accessToken, String tokenType, long expiresIn, IdentitySummary identity) {}
}
