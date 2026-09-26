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
    private static final String OSSIE_MODE = "OSSIE";
    private boolean enabled;

    /** 发布模式默认延续旧 Ossie 消费，DBT 必须在真实服务验收后显式启用。 */
    private String mode = OSSIE_MODE;

    /** 仓库的稳定标识，作为幂等检查点与对象归属作用域。 */
    private String repositoryId;

    private String remote;
    private String executable = "git";
    private Path cacheDirectory = Path.of(".git-sync-cache");

    /** 已存在的平台数据源 UUID，禁用时可以不配置。 */
    private UUID dataSourceId;

    private String schema;
    private String modelName;

    /** dbt 模式下部署配置的稳定项目标识，不接受请求覆盖。 */
    private String projectId;

    /** dbt 项目在受信任 Git 仓库中的固定子目录。 */
    private String projectSubdir;

    /** 外部运行服务中固定的 StarRocks profile 绑定标识。 */
    private String profileBindingId;

    /** 会影响 dbt 编译或结果的受控 profile、环境与工具链配置版本。 */
    private String configVersion;

    /** DBT 模式下必须匹配 READY 产物的固定 dbt/MetricFlow 工具链版本。 */
    private String toolchainVersion;

    /** 仅通过环境或密钥配置注入；不记录、不对外返回。 */
    private String webhookSecret;

    private Duration pollInterval = Duration.ofSeconds(30);
    private Duration gitTimeout = Duration.ofSeconds(30);
}
