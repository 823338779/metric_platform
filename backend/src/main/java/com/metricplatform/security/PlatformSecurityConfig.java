package com.metricplatform.security;

import static com.metricplatform.security.PlatformScopePolicy.METRIC_CATALOG_READ;
import static com.metricplatform.security.PlatformScopePolicy.METRIC_MEMBER_READ;
import static com.metricplatform.security.PlatformScopePolicy.METRIC_QUERY_EXECUTE;
import static com.metricplatform.security.PlatformScopePolicy.METRIC_QUERY_VALIDATE;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                                        .authenticated()
                                        .requestMatchers("/api/v1/admin/**")
                                        .hasRole("PLATFORM_ADMIN")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/capabilities")
                                        .hasAuthority(scope(METRIC_CATALOG_READ))
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/v1/datasets/*/semantic-model")
                                        .hasAuthority(scope(METRIC_CATALOG_READ))
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/semantic-objects:search")
                                        .hasAuthority(scope(METRIC_CATALOG_READ))
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/semantic-objects:resolve")
                                        .hasAuthority(scope(METRIC_CATALOG_READ))
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/dimensions/*/members:search")
                                        .hasAuthority(scope(METRIC_MEMBER_READ))
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/queries:validate")
                                        .hasAuthority(scope(METRIC_QUERY_VALIDATE))
                                        .requestMatchers(HttpMethod.POST, "/api/v1/queries")
                                        .hasAuthority(scope(METRIC_QUERY_EXECUTE))
                                        .requestMatchers("/api/v1/**")
                                        .hasAnyRole("PLATFORM_ADMIN", "SEMANTIC_EDITOR")
                                        .anyRequest()
                                        .permitAll())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static String scope(String value) {
        return "SCOPE_" + value;
    }
}
