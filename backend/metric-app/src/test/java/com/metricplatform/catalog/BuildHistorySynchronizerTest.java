package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.BuildContracts.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildHistorySynchronizerTest {
    @Test
    void restartReadsOnlyCommittedWatermark() {
        var gateway = mock(BuildGateway.class);
        var history = mock(BuildHistoryRepository.class);
        var page = new ChangePage(List.of(), "8");
        when(history.cursor()).thenReturn("8");
        when(gateway.changes("8", 200)).thenReturn(page);
        doThrow(new IllegalStateException("rollback")).doNothing().when(history).apply(page, "8");
        assertThatThrownBy(() -> new BuildHistorySynchronizer(gateway, history).syncOnce())
                .isInstanceOf(IllegalStateException.class);
        new BuildHistorySynchronizer(gateway, history).syncOnce();
        verify(gateway, times(2)).changes("8", 200);
        verify(history, times(2)).apply(page, "8");
    }
}
