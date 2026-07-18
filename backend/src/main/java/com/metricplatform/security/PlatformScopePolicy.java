package com.metricplatform.security;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PlatformScopePolicy {
    public static final String METRIC_CATALOG_READ = "metric.catalog.read";
    public static final String METRIC_MEMBER_READ = "metric.member.read";
    public static final String METRIC_QUERY_VALIDATE = "metric.query.validate";
    public static final String METRIC_QUERY_EXECUTE = "metric.query.execute";
    public static final String SEMANTIC_MANAGE = "semantic.manage";
    public static final String PLATFORM_IDENTITY_MANAGE = "platform.identity.manage";

    private static final Set<String> QUERY_SCOPES =
            Set.of(
                    METRIC_CATALOG_READ,
                    METRIC_MEMBER_READ,
                    METRIC_QUERY_VALIDATE,
                    METRIC_QUERY_EXECUTE);

    private PlatformScopePolicy() {}

    public static Set<String> scopesFor(Set<String> roles) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        for (String role : roles == null ? Set.<String>of() : roles) {
            switch (role) {
                case "METRIC_QUERY_CLIENT" -> scopes.addAll(QUERY_SCOPES);
                case "SEMANTIC_EDITOR" -> {
                    scopes.addAll(QUERY_SCOPES);
                    scopes.add(SEMANTIC_MANAGE);
                }
                case "PLATFORM_ADMIN" -> {
                    scopes.addAll(QUERY_SCOPES);
                    scopes.add(SEMANTIC_MANAGE);
                    scopes.add(PLATFORM_IDENTITY_MANAGE);
                }
                default -> {
                    // Unknown roles intentionally grant no scopes.
                }
            }
        }
        return Set.copyOf(scopes);
    }
}
