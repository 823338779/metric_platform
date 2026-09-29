package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceBoundaryTest {
    @Test
    void persistenceImplementationAndMigrationsStayInPersistenceModule() throws IOException {
        Path appRoot = Path.of(System.getProperty("basedir"), "src/main/java/com/metricplatform");
        Path persistenceRoot =
                Path.of(System.getProperty("basedir"), "..", "metric-persistence", "src/main");
        List<String> violations = new ArrayList<>();

        assertThat(Files.exists(appRoot.resolve("persistence"))).isFalse();
        assertThat(
                        Files.exists(
                                Path.of(
                                        System.getProperty("basedir"),
                                        "src/main/resources/db/migration")))
                .isFalse();
        assertThat(
                        Files.isDirectory(
                                persistenceRoot.resolve("java/com/metricplatform/persistence")))
                .isTrue();
        assertThat(Files.isDirectory(persistenceRoot.resolve("resources/db/migration"))).isTrue();

        try (var files = Files.walk(appRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = appRoot.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file);
                if (source.contains("import com.baomidou.mybatisplus.")
                        || source.contains("import com.metricplatform.persistence.entity.")
                        || source.contains("import com.metricplatform.persistence.mapper.")
                        || source.contains("import com.metricplatform.persistence.service.")
                        || source.contains("import com.metricplatform.persistence.redis.")
                        || source.contains("import com.metricplatform.persistence.store.")
                        || source.contains("import com.metricplatform.persistence.catalog.")
                        || source.contains("import com.metricplatform.persistence.publication.")
                        || source.contains("extends ServiceImpl<")) {
                    violations.add(relative);
                }
            }
        }

        assertThat(violations).isEmpty();
    }
}
