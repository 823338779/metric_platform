package com.metricplatform.integration.ontology;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.metricplatform.catalog.WarehouseMetadataPolicy.Instance;
import com.metricplatform.engine.warehouse.StarRocksColumnReader;
import com.metricplatform.ontology.WarehouseColumns.TableTarget;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;

/** DDL 和临时账号只在本次测试创建的容器中使用。 */
@EnabledIfSystemProperty(named = "ontology.starrocks.it", matches = "true")
class StarRocksOntologyMetadataIntegrationTest {
    private static boolean execute(GenericContainer<?> fixture, String command) throws Exception {
        return fixture.execInContainer(
                                "mysql", "-h", "127.0.0.1", "-P", "9030", "-uroot", "-e", command)
                        .getExitCode()
                == 0;
    }

    @Test
    void metadataOnEmptyAliasedTableReflectsDdlAndVisibility() throws Exception {
        try (var db =
                new GenericContainer<>("starrocks/allin1-ubuntu:3.5.19")
                        .withExposedPorts(9030)
                        .withStartupTimeout(Duration.ofMinutes(3))) {
            db.start();
            String url =
                    "jdbc:mysql://"
                            + db.getHost()
                            + ":"
                            + db.getMappedPort(9030)
                            + "/?useSSL=false";
            await().atMost(Duration.ofMinutes(3))
                    .until(() -> execute(db, "CREATE DATABASE IF NOT EXISTS ontology_fixture"));
            var target =
                    new TableTarget(
                            "fixture", "default_catalog", "ontology_fixture", "orders_alias");
            var reader = new StarRocksColumnReader();
            var password = UUID.randomUUID().toString();
            var account =
                    new Instance(
                            "fixture", true, url, "ontology_reader", password, "1", true, Map.of());
            await().atMost(Duration.ofMinutes(2))
                    .until(
                            () ->
                                    execute(
                                            db,
                                            "CREATE TABLE IF NOT EXISTS ontology_fixture.orders_alias (id BIGINT, amount DECIMAL(18,2)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')"));
            assertThat(execute(db, "CREATE USER ontology_reader IDENTIFIED BY '" + password + "'"))
                    .isTrue();
            var hidden = reader.read(target, account);
            assertThat(hidden.complete()).isFalse();
            assertThat(
                            execute(
                                    db,
                                    "GRANT SELECT ON TABLE ontology_fixture.orders_alias TO USER ontology_reader"))
                    .isTrue();
            var visible = reader.read(target, account);
            assertThat(visible.types().get("id")).startsWithIgnoringCase("bigint");
            assertThat(visible.types().get("amount")).isEqualToIgnoringCase("decimal(18, 2)");
            assertThat(execute(db, "ALTER TABLE ontology_fixture.orders_alias DROP COLUMN amount"))
                    .isTrue();
            await().atMost(Duration.ofSeconds(90))
                    .untilAsserted(
                            () -> {
                                var after = reader.read(target, account);
                                assertThat(after.complete()).isTrue();
                                assertThat(after.types())
                                        .containsKey("id")
                                        .doesNotContainKey("amount");
                            });
            assertThat(
                            execute(
                                    db,
                                    "REVOKE SELECT ON TABLE ontology_fixture.orders_alias FROM USER ontology_reader"))
                    .isTrue();
            assertThat(reader.read(target, account).complete()).isFalse();
        }
    }
}
