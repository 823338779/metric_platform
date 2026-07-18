package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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

    /** Source-compatible fixture hook; secret references are no longer persisted or used. */
    @Deprecated(forRemoval = true)
    public void setSecretReference(String ignored) {}
}
