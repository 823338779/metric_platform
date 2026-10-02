package com.metricplatform.gitsync;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.engine.dbt.git.DbtProjectReader;
import com.metricplatform.publication.ProjectSourceReader;
import com.metricplatform.publication.PublicationGateway;
import com.metricplatform.publication.ReleaseCoordinator;
import com.metricplatform.publication.ReleaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 手动发布按固定绑定装配，自动同步开关只控制启动扫描与 Webhook。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GitSyncProperties.class)
public class GitSyncConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "metric-platform.git-sync", name = "remote")
    ProjectSourceReader projectSourceReader(GitSyncProperties properties) {
        return new DbtProjectReader(
                properties.getExecutable(), properties.getRemote(),
                properties.getCacheDirectory(), properties.getGitTimeout());
    }

    @Bean
    @ConditionalOnExpression("'${metric.publication.mode:legacy}' == 'legacy'")
    @ConditionalOnProperty(
            name = {"metric-platform.git-sync.remote", "metric-platform.dbt-runtime.enabled"})
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
                || p.getPollInterval() == null
                || p.getPollInterval().toMillis() < 1000)
            throw new IllegalStateException("项目 Git 同步配置不完整或轮询间隔过短");
        return new ReleaseCoordinator(reader, releases, publication, runtime, p);
    }
}
