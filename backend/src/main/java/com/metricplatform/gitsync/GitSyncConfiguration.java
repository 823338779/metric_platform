package com.metricplatform.gitsync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 开关关闭时不访问 Git；启用前验证不可由模型选择的固定绑定。 */
@Configuration
@EnableConfigurationProperties(GitSyncProperties.class)
public class GitSyncConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "metric-platform.git-sync",
            name = "enabled",
            havingValue = "true")
    GitSyncCoordinator gitSyncCoordinator(GitSyncProperties p, GitImportPort importer) {
        if (p.getRepositoryId() == null
                || !p.getRepositoryId().matches("[a-z][a-z0-9_-]{0,79}")
                || p.getDataSourceId() == null
                || p.getSchema() == null
                || p.getSchema().isBlank()
                || p.getModelName() == null
                || p.getModelName().isBlank()
                || p.getWebhookSecret() == null
                || p.getWebhookSecret().length() < 16
                || p.getPollInterval() == null
                || p.getPollInterval().toMillis() < 1000)
            throw new IllegalStateException("Git 同步配置不完整或轮询间隔过短");
        return new GitSyncCoordinator(
                new GitSnapshotReader(
                        p.getExecutable(), p.getRemote(), p.getCacheDirectory(), p.getGitTimeout()),
                new OssieParser(),
                importer,
                p.getPollInterval());
    }
}
