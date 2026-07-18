package com.metricplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class PlatformSecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(
                                                                HttpServletResponse
                                                                        .SC_UNAUTHORIZED))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(
                                                                HttpServletResponse.SC_FORBIDDEN)))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/api/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .addFilterBefore(new BearerFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static final class BearerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, jakarta.servlet.ServletException {
            String header = request.getHeader("Authorization");
            String role = null;
            String id = null;
            if ("Bearer admin-token".equals(header)) {
                role = "PLATFORM_ADMIN";
                id = "admin";
            }
            if ("Bearer query-token".equals(header)) {
                role = "METRIC_QUERY_CLIENT";
                id = "query-client";
            }
            if (role != null) {
                PlatformPrincipal principal = new PlatformPrincipal(id, role);
                var auth =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }
}
