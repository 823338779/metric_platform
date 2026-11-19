package com.metricplatform.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;

public record PlatformPrincipal(
        UUID identityId,
        String subject,
        String displayName,
        UUID credentialId,
        long authVersion,
        /** 登录账号所属租户。 */
        String tenantId,
        /** 由当前租户管理者引用推导的角色。 */
        Role role) {
    public enum Role {
        TENANT_MANAGER,
        READ_ONLY
    }

    /** 仅认证过滤器可以建立业务身份。 */
    public static PlatformPrincipal current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PlatformPrincipal actor) return actor;
        throw new BadCredentialsException("登录身份无效");
    }

    public void requireManager() {
        if (!manager()) throw new AccessDeniedException("需要租户管理者");
    }

    public boolean manager() {
        return role == Role.TENANT_MANAGER;
    }
}
