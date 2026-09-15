package com.metricplatform.gitsync;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单个仓库与平台数据源的固定绑定；远端、数据库与密钥不能由通知或 YAML 修改。 */
@Getter
@Setter
@ConfigurationProperties("metric-platform.git-sync")
public class GitSyncProperties {
    private boolean enabled;

    /** 仓库的稳定标识，作为幂等检查点与对象归属作用域。 */
    private String repositoryId;

    private String remote;
    private String executable = "git";
    private Path cacheDirectory = Path.of(".git-sync-cache");

    /** 已存在的平台数据源 UUID，禁用时可以不配置。 */
    private UUID dataSourceId;

    private String schema;
    private String modelName;

    /** 仅通过环境或密钥配置注入；不记录、不对外返回。 */
    private String webhookSecret;

    private Duration pollInterval = Duration.ofSeconds(30);
    private Duration gitTimeout = Duration.ofSeconds(30);
}
