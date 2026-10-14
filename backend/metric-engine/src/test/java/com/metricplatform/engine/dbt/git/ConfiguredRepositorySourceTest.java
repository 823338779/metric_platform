package com.metricplatform.engine.dbt.git;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.environment.RepositorySource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfiguredRepositorySourceTest {
    private HttpServer server;
    private String repository;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/metrics.git";
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsOnlyHeadsFromConfiguredRemoteAndPreservesBranchNames() {
        advertise("main", "feature/report", "release/2026");
        var source = new ConfiguredRepositorySource(repository);
        assertThat(source.repository()).isEqualTo(repository);
        assertThat(source.branches()).containsExactly("feature/report", "main", "release/2026");
    }

    @Test
    void emptyRepositoryReturnsNoBranches() {
        advertise();
        assertThat(new ConfiguredRepositorySource(repository).branches()).isEmpty();
    }

    @Test
    void remoteFailureDoesNotExposeResponseOrAddress() {
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "private upstream diagnostic".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(403, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        assertThatThrownBy(() -> new ConfiguredRepositorySource(repository).branches())
                .isInstanceOf(RepositorySource.UnavailableException.class)
                .hasMessageNotContaining(repository)
                .hasMessageNotContaining("private upstream diagnostic");
    }

    @Test
    void rejectsUnsafeOrCredentialBearingConfiguration() {
        for (String value :
                new String[] {
                    "file:///tmp/repo",
                    "--upload-pack=bad",
                    "ext::command",
                    "https://user:secret@example.test/repo",
                    repository + "?token=secret"
                }) {
            assertThatThrownBy(() -> new ConfiguredRepositorySource(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void advertise(String... branches) {
        String hash = "a".repeat(40);
        var refs = new StringBuilder();
        for (String branch : branches)
            refs.append(hash).append("\trefs/heads/").append(branch).append("\n");
        refs.append(hash).append("\trefs/tags/v1\n");
        server.createContext(
                "/metrics.git/info/refs",
                exchange -> {
                    byte[] body = refs.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.createContext(
                "/metrics.git/HEAD",
                exchange -> {
                    byte[] body = "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
    }
}
