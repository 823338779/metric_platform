package com.metricplatform.security;

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
                                        .requestMatchers("/api/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
