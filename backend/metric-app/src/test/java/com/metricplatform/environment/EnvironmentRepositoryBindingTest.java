package com.metricplatform.environment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.metricplatform.environment.EnvironmentContracts.SubmitBuildCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvironmentRepositoryBindingTest {
    @Test
    void rejectsUnboundRepositoryBeforeReservingOrSubmitting() {
        var submissions = mock(BuildSubmissionStore.class);
        var gateway = mock(EnvironmentBuildGateway.class);
        var source = mock(RepositorySource.class);
        when(source.repository()).thenReturn("http://127.0.0.1:3000/agent-admin/metrics.git");
        var service = new EnvironmentBuildService(submissions, gateway, source);
        var command =
                new SubmitBuildCommand(
                        "https://other.test/metrics.git", "main", null, 1, "ON_SUCCESS", "key");
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), command, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅允许使用服务器绑定的 Git 仓库");
        verifyNoInteractions(submissions, gateway);
    }
}
