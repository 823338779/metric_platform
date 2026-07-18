package com.metricplatform.source;

public record JdbcConnectionSettings(
        String databaseType,
        String jdbcUrl,
        String username,
        String password,
        String defaultCatalog,
        String defaultSchema) {}
