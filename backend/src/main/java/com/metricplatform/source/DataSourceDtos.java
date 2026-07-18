package com.metricplatform.source;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class DataSourceDtos {
    private DataSourceDtos() {}

    public enum DatabaseType {
        STARROCKS,
        MYSQL,
        POSTGRESQL
    }

    public enum DataSourceStatus {
        ACTIVE,
        INACTIVE,
        DELETED
    }

    public enum HealthStatus {
        UNKNOWN,
        HEALTHY,
        UNHEALTHY
    }

    public record CreateDataSourceRequest(
            @NotBlank String name,
            String description,
            @NotNull DatabaseType databaseType,
            @NotBlank String jdbcUrl,
            @NotBlank String username,
            @NotNull String password,
            String defaultCatalog,
            String defaultSchema) {}

    public record UpdateDataSourceRequest(
            @NotBlank String name,
            String description,
            @NotNull DatabaseType databaseType,
            String jdbcUrl,
            @NotBlank String username,
            @NotNull String password,
            String defaultCatalog,
            String defaultSchema,
            @Min(0) long version) {}

    public record ConnectionSettingsRequest(
            @NotNull DatabaseType databaseType,
            @NotBlank String jdbcUrl,
            @NotBlank String username,
            @NotNull String password,
            String defaultCatalog,
            String defaultSchema) {}

    public record DataSourceSummary(
            UUID id,
            String name,
            String description,
            DatabaseType databaseType,
            String databaseName,
            DataSourceStatus status,
            HealthStatus healthStatus,
            LocalDateTime lastCheckedAt,
            Long lastLatencyMs) {}

    public record DataSourceDetail(
            UUID id,
            String name,
            String description,
            DatabaseType databaseType,
            String jdbcUrl,
            String username,
            String password,
            String defaultCatalog,
            String defaultSchema,
            DataSourceStatus status,
            HealthStatus healthStatus,
            LocalDateTime lastCheckedAt,
            Long lastLatencyMs,
            long version) {}

    public record ConnectionTestResponse(boolean successful, long latencyMs, String message) {}

    public record DependentDataset(UUID id, String name) {}

    public record DataSourceDeletionImpact(List<DependentDataset> datasets) {}

    public record CatalogResponse(String name) {}

    public record SchemaResponse(String catalogName, String schemaName) {}

    public record SourceObjectResponse(
            String catalogName, String schemaName, String objectName, String objectType) {}

    public record SourceFieldResponse(
            String physicalName,
            String physicalType,
            int jdbcType,
            int ordinal,
            boolean nullable) {}

    public record PreviewColumn(String name, String physicalType, int jdbcType) {}

    public record PreviewResponse(
            List<PreviewColumn> columns, List<List<Object>> rows, int limit) {}
}
