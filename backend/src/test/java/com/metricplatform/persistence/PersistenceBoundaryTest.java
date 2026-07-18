package com.metricplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceBoundaryTest {
    @Test
    void productionCodeAccessesMappersOnlyThroughPersistenceServices() throws IOException {
        Path sourceRoot =
                Path.of(System.getProperty("basedir"), "src/main/java/com/metricplatform");
        List<String> violations = new ArrayList<>();

        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("persistence/mapper/")
                        || relative.startsWith("persistence/service/")) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("import com.metricplatform.persistence.mapper.")) {
                    violations.add(relative);
                }
            }
        }

        assertThat(violations).isEmpty();
    }
}
