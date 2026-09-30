package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.metricplatform.engine.EngineClient;
import com.metricplatform.publication.PublicationGateway;
import com.metricplatform.publication.ReleaseCoordinator;
import com.metricplatform.publication.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManualGitRefreshConfigurationTest {
    @Test
    void missingConfigurationReturnsActionableErrorInsteadOfAcceptingRefresh() {
        new ApplicationContextRunner()
                .withUserConfiguration(GitSyncConfiguration.class, GitRefreshController.class)
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ReleaseCoordinator.class);
                            var response = context.getBean(GitRefreshController.class).refresh();
                            assertThat(response.getStatusCode().value()).isEqualTo(503);
                            assertThat(
                                            ((org.springframework.http.ProblemDetail)
                                                            response.getBody())
                                                    .getDetail())
                                    .contains("Git 仓库", "dbt 运行服务");
                        });
    }

    @Test
    void manualModeCreatesCoordinatorWithoutWebhookSecretOrStartupGitRead() {
        var releases = mock(ReleaseRepository.class);
        var runtime = mock(EngineClient.class);
        new ApplicationContextRunner()
                .withUserConfiguration(GitSyncConfiguration.class)
                .withBean(ReleaseRepository.class, () -> releases)
                .withBean(PublicationGateway.class, () -> mock(PublicationGateway.class))
                .withBean(EngineClient.class, () -> runtime)
                .withPropertyValues(
                        "metric-platform.git-sync.enabled=false",
                        "metric-platform.dbt-runtime.enabled=true",
                        "metric-platform.git-sync.remote=unused-remote",
                        "metric-platform.git-sync.executable=must-not-run-git",
                        "metric-platform.git-sync.repository-id=metrics",
                        "metric-platform.git-sync.project-id=sales",
                        "metric-platform.git-sync.project-subdir=.",
                        "metric-platform.git-sync.profile-binding-id=starrocks",
                        "metric-platform.git-sync.config-version=test",
                        "metric-platform.git-sync.toolchain-version=test")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReleaseCoordinator.class);
                            verify(releases, timeout(2000)).pending("sales");
                            verify(releases, after(200).never())
                                    .bindProject(any(), any(), any(), any());
                            verifyNoInteractions(runtime);
                        });
    }
}
