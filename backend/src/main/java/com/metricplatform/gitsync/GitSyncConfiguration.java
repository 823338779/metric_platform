package com.metricplatform.gitsync;

import com.metricplatform.dbt.DbtPublicationService;
import com.metricplatform.dbt.DbtReleaseCoordinator;
import com.metricplatform.dbt.DbtReleaseStore;
import com.metricplatform.dbt.DbtRuntimeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开关关闭时不访问 Git；启用前验证不可由模型选择的固定绑定。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GitSyncProperties.class)
public class GitSyncConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "metric-platform.git-sync",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnExpression("'${metric-platform.git-sync.mode:OSSIE}' == 'OSSIE'")
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

    @Bean
    @ConditionalOnProperty(
            prefix = "metric-platform.git-sync",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnExpression("'${metric-platform.git-sync.mode:OSSIE}' == 'DBT'")
    DbtReleaseCoordinator dbtReleaseCoordinator(
            GitSyncProperties p,
            DbtReleaseStore releases,
            DbtPublicationService publication,
            DbtRuntimeClient runtime) {
        if (p.getRepositoryId() == null
                || !p.getRepositoryId().matches("[a-z][a-z0-9_-]{0,79}")
                || p.getProjectId() == null
                || !p.getProjectId().matches("[a-z][a-z0-9_-]{0,79}")
                || p.getProjectSubdir() == null
                || p.getProjectSubdir().isBlank()
                || p.getProfileBindingId() == null
                || p.getProfileBindingId().isBlank()
                || p.getConfigVersion() == null
                || p.getConfigVersion().isBlank()
                || p.getToolchainVersion() == null
                || p.getToolchainVersion().isBlank()
                || p.getWebhookSecret() == null
                || p.getWebhookSecret().length() < 16
                || p.getPollInterval() == null
                || p.getPollInterval().toMillis() < 1000)
            throw new IllegalStateException("dbt Git 同步配置不完整或轮询间隔过短");
        return new DbtReleaseCoordinator(
                new GitSnapshotReader(
                        p.getExecutable(), p.getRemote(), p.getCacheDirectory(), p.getGitTimeout()),
                releases,
                publication,
                runtime,
                p);
    }
}
