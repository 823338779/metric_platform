package com.metricplatform.gitsync;

import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单个语义项目的固定 Git 与运行服务绑定；远端与密钥不能由通知覆盖。 */
@Getter
@Setter
@ConfigurationProperties("metric-platform.git-sync")
public class GitSyncProperties {
    /** 控制启动自动扫描与 Webhook；关闭时仍允许已登录用户手动刷新。 */
    private boolean enabled;

    /** 仓库的稳定标识，作为幂等检查点与对象归属作用域。 */
    private String repositoryId;

    private String remote;
    private String executable = "git";
    private Path cacheDirectory = Path.of(".git-sync-cache");

    /** 部署配置的稳定项目标识，不接受请求覆盖。 */
    private String projectId;

    /** 语义项目在受信任 Git 仓库中的固定子目录。 */
    private String projectSubdir;

    /** 外部运行服务中固定的 StarRocks profile 绑定标识。 */
    private String profileBindingId;

    /** StarRocks 中固定的 dbt 目标 schema；为空时沿用独立 run schema。 */
    private String schemaName;

    /** 会影响项目编译或结果的受控 profile、环境与工具链配置版本。 */
    private String configVersion;

    /** 必须匹配 READY 产物的固定计算引擎工具链版本。 */
    private String toolchainVersion;

    /** 仅用于旧自定义协议的签名密钥；Forgejo 原生通知不使用。 */
    private String webhookSecret;

    /** Forgejo 载荷中的 owner/repository 全名；为空时不接受 Forgejo 原生通知。 */
    private String forgejoRepository;

    private Duration pollInterval = Duration.ofSeconds(30);
    private Duration gitTimeout = Duration.ofSeconds(30);
}
