package com.metricplatform.security;

import com.metricplatform.identity.api.TenantOperationsController;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class PlatformSecurityConfig {
    private static final String OPS_HEADER = "X-Operations-Token";

    /** 独立处理运维请求，业务令牌不能取得租户开通权限。 */
    @Bean
    @Order(1)
    SecurityFilterChain operations(
            HttpSecurity http, @Value("${metric-platform.operations.token:}") String secret)
            throws Exception {
        return http.securityMatcher(TenantOperationsController.PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.anyRequest()
                                        .access(
                                                (auth, context) -> {
                                                    String supplied =
                                                            context.getRequest()
                                                                    .getHeader(OPS_HEADER);
                                                    boolean allowed =
                                                            !secret.isBlank()
                                                                    && supplied != null
                                                                    && java.security.MessageDigest
                                                                            .isEqual(
                                                                                    secret.getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8),
                                                                                    supplied
                                                                                            .getBytes(
                                                                                                    StandardCharsets
                                                                                                            .UTF_8));
                                                    return new AuthorizationDecision(allowed);
                                                }))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint((req, res, ex) -> res.sendError(401))
                                        .accessDeniedHandler((req, res, ex) -> res.sendError(403)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, PlatformTokenAuthenticationFilter tokenFilter)
            throws Exception {
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
                                a.requestMatchers(HttpMethod.POST, "/api/v1/auth/token")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/dbt/contract")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                                        .authenticated()
                                        .requestMatchers("/api/dbt/**", "/api/v1/**", "/api/v2/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
