package com.metricplatform.gitsync;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.dbt.git.DbtProjectReader;
import com.metricplatform.publication.ProjectSourceReader;
import com.metricplatform.publication.PublicationGateway;
import com.metricplatform.publication.ReleaseCoordinator;
import com.metricplatform.publication.ReleaseRepository;
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
    ProjectSourceReader projectSourceReader(GitSyncProperties properties) {
        return new DbtProjectReader(
                properties.getExecutable(), properties.getRemote(),
                properties.getCacheDirectory(), properties.getGitTimeout());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "metric-platform.git-sync",
            name = "enabled",
            havingValue = "true")
    ReleaseCoordinator releaseCoordinator(
            GitSyncProperties p,
            ProjectSourceReader reader,
            ReleaseRepository releases,
            PublicationGateway publication,
            EngineClient runtime) {
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
            throw new IllegalStateException("项目 Git 同步配置不完整或轮询间隔过短");
        return new ReleaseCoordinator(reader, releases, publication, runtime, p);
    }
}
