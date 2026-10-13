package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.api.BuildHistoryController;

import org.junit.jupiter.api.Test;

import java.util.List;

class BuildHistorySynchronizerTest {
    private static final java.util.UUID ENVIRONMENT_ID =
            java.util.UUID.fromString("12345678-1234-4234-8234-000000000001");

    @Test
    void liveHistoryPropagatesServiceFailureWithoutReadingLegacyProjection() {
        var gateway = mock(BuildGateway.class);
        var controller = new BuildHistoryController(gateway);
        var filter = new BuildFilter(ENVIRONMENT_ID, "repo", null, null, "cursor", 50);
        var page = new BuildPage(List.of(), "next");
        when(gateway.listBuilds(filter))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(page);
        assertThatThrownBy(() -> controller.list(ENVIRONMENT_ID, "repo", null, null, "cursor", 50))
                .isInstanceOf(IllegalStateException.class);
        assertThat(controller.list(ENVIRONMENT_ID, "repo", null, null, "cursor", 50))
                .isEqualTo(page);
        verify(gateway, times(2)).listBuilds(filter);
        assertThat(BuildGateway.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("changes"));
    }
}
