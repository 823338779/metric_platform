package com.metricplatform.security;

import java.util.Set;
import java.util.UUID;

public record PlatformPrincipal(
        UUID identityId,
        String subject,
        String displayName,
        UUID tenantId,
        Set<UUID> workspaceIds,
        Set<String> roles,
        Set<String> scopes,
        String clientId,
        String authenticationMethod,
        UUID credentialId,
        long authVersion) {
    public PlatformPrincipal {
        workspaceIds = Set.copyOf(workspaceIds == null ? Set.of() : workspaceIds);
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
