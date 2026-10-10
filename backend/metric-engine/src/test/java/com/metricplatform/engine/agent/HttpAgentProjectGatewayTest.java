package com.metricplatform.engine.agent;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class HttpAgentProjectGatewayTest {
    @Test
    void projectAndBranchesComeFromAgent() throws Exception {
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    paths.add(e.getRequestURI().getPath());
                    String body =
                            e.getRequestURI().getPath().endsWith("branches")
                                    ? "{\"items\":[{\"branchName\":\"feature/a\",\"headSha\":\"abc\"}]}"
                                    : "{\"items\":[{\"projectId\":\"p1\",\"name\":\"demo\",\"version\":1,\"developmentConfigured\":true,\"repository\":\"repo\"}],\"nextCursor\":null}";
                    var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, bytes.length);
                    try (var out = e.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            var gateway =
                    new HttpAgentProjectGateway(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            Duration.ofSeconds(2),
                            null);
            assertThat(gateway.listProjects(null, 50).items().getFirst().repository())
                    .isEqualTo("repo");
            var branches = gateway.listBranches("p1");
            assertThat(branches.items().getFirst().branchName()).isEqualTo("feature/a");
            assertThat(branches.cached()).isFalse();
            assertThat(branches.observedAt()).isNotNull();
            assertThat(paths)
                    .containsExactly(
                            "/api/v1/agent-projects", "/api/v1/agent-projects/p1/branches");
        } finally {
            server.stop(0);
        }
    }
}
