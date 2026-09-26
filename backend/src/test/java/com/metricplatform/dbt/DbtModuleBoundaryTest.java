package com.metricplatform.dbt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 检查 dbt 库模块不反向依赖可执行 backend。 */
class DbtModuleBoundaryTest {
    @Test
    void moduleBoundariesCompileWithoutBackendReference() throws Exception {
        for (String module :
                new String[] {"metric-core", "metric-persistence", "metric-runtime-client"}) {
            Path pom = Path.of("..", module, "pom.xml");
            assertTrue(Files.isRegularFile(pom), module);
            assertFalse(Files.readString(pom).contains("<artifactId>backend</artifactId>"), module);
        }
    }
}
