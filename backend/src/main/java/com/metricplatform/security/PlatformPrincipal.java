package com.metricplatform.security;

import java.util.UUID;

public record PlatformPrincipal(
        UUID identityId,
        String subject,
        String displayName,
        UUID credentialId,
        long authVersion) {}
