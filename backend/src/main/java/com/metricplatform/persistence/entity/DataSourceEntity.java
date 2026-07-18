package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import java.util.UUID;

@TableName("data_source")
public class DataSourceEntity extends BaseAuditEntity {
    @TableId private UUID id;
    private String name;
    private String description;
    private String databaseType;
    private String driverClass;
    private String jdbcUrl;
    private String databaseName;
    private String username;
    private String password;
    private String defaultCatalog;
    private String defaultSchema;
    private String status;
    private String healthStatus;
    private LocalDateTime lastCheckedAt;
    private Long lastLatencyMs;
    @Version private long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        id = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        name = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String value) {
        description = value;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(String value) {
        databaseType = value;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String value) {
        driverClass = value;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String value) {
        jdbcUrl = value;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String value) {
        databaseName = value;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        username = value;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value;
    }

    /** Source-compatible fixture hook; secret references are no longer persisted or used. */
    @Deprecated(forRemoval = true)
    public void setSecretReference(String ignored) {}

    public String getDefaultCatalog() {
        return defaultCatalog;
    }

    public void setDefaultCatalog(String value) {
        defaultCatalog = value;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String value) {
        defaultSchema = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String value) {
        healthStatus = value;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(LocalDateTime value) {
        lastCheckedAt = value;
    }

    public Long getLastLatencyMs() {
        return lastLatencyMs;
    }

    public void setLastLatencyMs(Long value) {
        lastLatencyMs = value;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long value) {
        version = value;
    }
}
