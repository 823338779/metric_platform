package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitSyncCoordinatorTest {
    @Test
    void startupPublishesAndWebhookSkipsAlreadyAppliedCommit() throws Exception {
        var reader = mock(GitSnapshotReader.class);
        var parser = mock(OssieParser.class);
        var importer = mock(GitImportPort.class);
        var applied = new AtomicReference<String>();
        var published = new CountDownLatch(1);
        var healthy = new CountDownLatch(1);
        var model = mock(OssieModel.class);
        when(reader.read()).thenReturn(new GitSnapshot("a", new byte[] {1}));
        when(parser.parse(any())).thenReturn(model);
        when(importer.state())
                .thenAnswer(
                        call ->
                                new GitImportPort.State(
                                        "metrics",
                                        applied.get(),
                                        "model",
                                        null,
                                        null,
                                        Instant.now()));
        when(importer.apply(isNull(), any(), same(model)))
                .thenAnswer(
                        call -> {
                            applied.set("a");
                            published.countDown();
                            return true;
                        });
        doAnswer(
                        call -> {
                            healthy.countDown();
                            return null;
                        })
                .when(importer)
                .healthy("a");
        var coordinator = new GitSyncCoordinator(reader, parser, importer, Duration.ofHours(1));
        try {
            coordinator.start();
            assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
            coordinator.requestRefresh();
            assertThat(healthy.await(5, TimeUnit.SECONDS)).isTrue();
            verify(importer, times(1)).apply(isNull(), any(), same(model));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void pollingSurvivesFailureAndClearsErrorAtSameCommit() throws Exception {
        var reader = mock(GitSnapshotReader.class);
        var importer = mock(GitImportPort.class);
        var recovered = new CountDownLatch(1);
        when(importer.state())
                .thenReturn(
                        new GitImportPort.State(
                                "metrics", "a", "model", null, null, Instant.now()));
        when(reader.read())
                .thenThrow(new IllegalStateException("private remote detail"))
                .thenReturn(new GitSnapshot("a", new byte[] {1}));
        doAnswer(
                        call -> {
                            recovered.countDown();
                            return null;
                        })
                .when(importer)
                .healthy("a");
        var coordinator =
                new GitSyncCoordinator(
                        reader, mock(OssieParser.class), importer, Duration.ofMillis(10));
        try {
            coordinator.start();
            assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue();
            verify(importer)
                    .failed(
                            eq("a"),
                            eq("SYNC_FAILED"),
                            argThat(message -> !message.contains("private")));
            verify(importer, never()).apply(any(), any(), any());
        } finally {
            coordinator.close();
        }
    }
}
