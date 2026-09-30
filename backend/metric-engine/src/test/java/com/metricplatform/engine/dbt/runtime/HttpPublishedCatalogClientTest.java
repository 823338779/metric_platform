package com.metricplatform.engine.dbt.runtime;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.PublishedCatalogGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpPublishedCatalogClientTest {
    @Test
    void acceptsProducerFixtureAndRejectsIncompleteDisplayProtocol() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/publication/catalog-v1.json")) {
            var fixture = mapper.readTree(stream);
            fixture.path("resources").forEach(PublishedProtocol::resource);
        }
        assertThatThrownBy(() -> PublishedProtocol.catalog(mapper.readTree("{\"resources\":[]}")))
                .isInstanceOfSatisfying(
                        PublishedCatalogGateway.Failure.class,
                        error -> assertThat(error.status()).isEqualTo(502));
    }

    @Test
    void preservesPublishedIdentityAndGoneStatus() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = UUID.randomUUID();
        server.createContext(
                "/v2/projects/sales/publication",
                exchange -> {
                    var bytes =
                            "{\"projectId\":\"sales\",\"activePublication\":null}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.createContext(
                "/v2/projects/sales/releases/" + release + "/catalog",
                exchange -> {
                    exchange.sendResponseHeaders(410, -1);
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    new HttpPublishedCatalogClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            Duration.ofSeconds(2));
            assertThat(client.publication("sales").get("activePublication").isNull()).isTrue();
            assertThatThrownBy(() -> client.catalog("sales", release, "", "", 1, 50))
                    .isInstanceOfSatisfying(
                            PublishedCatalogGateway.Failure.class,
                            error -> assertThat(error.status()).isEqualTo(410));
        } finally {
            server.stop(0);
        }
    }
}
