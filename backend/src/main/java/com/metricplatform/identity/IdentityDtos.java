package com.metricplatform.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class IdentityDtos {
    private IdentityDtos() {}

    public record AuthTokenRequest(
            @NotBlank String username, @NotBlank @Size(max = 1024) String password) {}

    public record IdentitySummary(
            UUID identityId,
            String subject,
            String displayName,
            UUID tenantId,
            Set<UUID> workspaceIds,
            Set<String> roles,
            Set<String> scopes,
            long authVersion) {
        public IdentitySummary {
            workspaceIds = Set.copyOf(workspaceIds == null ? Set.of() : workspaceIds);
            roles = Set.copyOf(roles == null ? Set.of() : roles);
            scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
        }
    }

    public record AuthTokenResponse(
            String accessToken, String tokenType, long expiresIn, IdentitySummary identity) {}

    public record CreatePlatformUserRequest(
            @NotBlank String tenantKey,
            @NotBlank String username,
            @NotBlank String displayName,
            @NotBlank @Size(max = 1024) String password,
            @NotBlank String roleCode,
            Set<UUID> workspaceIds) {
        public CreatePlatformUserRequest {
            workspaceIds = Set.copyOf(workspaceIds == null ? Set.of() : workspaceIds);
        }
    }

    public record ReplacePlatformPasswordRequest(@NotBlank @Size(max = 1024) String password) {}

    public record SetPlatformUserStatusRequest(@NotBlank String status) {}

    public record DatasetGrantRequest(
            @NotNull UUID datasetId, boolean discoverAllowed, boolean queryAllowed) {}

    public record ReplaceDatasetGrantsRequest(List<DatasetGrantRequest> grants) {
        public ReplaceDatasetGrantsRequest {
            grants = List.copyOf(grants == null ? List.of() : grants);
        }
    }
}
