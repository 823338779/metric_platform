package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.api.BuildHistoryController;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildHistorySynchronizerTest {
    @Test
    void liveHistoryPropagatesServiceFailureWithoutReadingLegacyProjection() {
        var gateway = mock(BuildGateway.class);
        var controller = new BuildHistoryController(gateway);
        var filter = new BuildFilter("repo", null, "cursor", 50);
        var page = new BuildPage(List.of(), "next");
        when(gateway.listBuilds(filter))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn(page);
        assertThatThrownBy(() -> controller.list("repo", null, "cursor", 50))
                .isInstanceOf(IllegalStateException.class);
        assertThat(controller.list("repo", null, "cursor", 50)).isEqualTo(page);
        verify(gateway, times(2)).listBuilds(filter);
        assertThat(BuildGateway.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("changes"));
    }
}
