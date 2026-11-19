package com.metricplatform.security;

import com.metricplatform.identity.TenantScope;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class PlatformTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String PREFIX = "Bearer ";

    private final PlatformAuthenticationService authentication;

    public PlatformTokenAuthenticationFilter(PlatformAuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (SecurityContextHolder.getContext().getAuthentication() == null
                && header != null
                && header.startsWith(PREFIX)) {
            try {
                PlatformPrincipal principal =
                        authentication.authenticate(header.substring(PREFIX.length()).trim());
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        principal, null, java.util.List.of()));
            } catch (BadCredentialsException invalidToken) {
                SecurityContextHolder.clearContext();
            }
        }
        var authenticated = SecurityContextHolder.getContext().getAuthentication();
        // 业务 SQL 的租户来自已验证身份，请求结束时必须清理线程上的作用域。
        if (authenticated != null
                && authenticated.isAuthenticated()
                && authenticated.getPrincipal() instanceof PlatformPrincipal principal) {
            try (var scope = TenantScope.open(principal.tenantId())) {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
