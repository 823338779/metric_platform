package com.metricplatform.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.gitsync.GitSyncConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublicationProxyConfigurationTest {
    @Test
    void proxyModeDoesNotCreatePlatformPublisherEvenWithLegacyConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(GitSyncConfiguration.class)
                .withBean(ReleaseRepository.class, () -> mock(ReleaseRepository.class))
                .withBean(PublicationGateway.class, () -> mock(PublicationGateway.class))
                .withBean(EngineClient.class, () -> mock(EngineClient.class))
                .withPropertyValues(
                        "metric.publication.mode=service-proxy",
                        "metric-platform.git-sync.remote=unused",
                        "metric-platform.dbt-runtime.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ReleaseCoordinator.class);
                        });
    }
}
