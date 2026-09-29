package com.metricplatform.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 平台核心声明端口，引擎与持久化实现端口，应用负责装配。 */
class ModuleBoundaryTest {
    private static final Pattern MODULE = Pattern.compile("<module>[^<]+</module>");
    private static final String CORE = "metric-core", PERSISTENCE = "metric-persistence";
    private static final String ENGINE = "metric-engine", APP = "metric-app";
    private static final String JAVA_ROOT = "src/main/java/com/metricplatform";
    private static final String JAVA_SUFFIX = ".java";
    private static final String ENGINE_IMPLEMENTATION = "com.metricplatform.engine.dbt.";
    private static final String LEGACY_PACKAGE = "com.metricplatform.dbt";
    private static final String ENGINE_ASSEMBLY = "gitsync/GitSyncConfiguration.java";
    private static final Pattern DBT_TYPE =
            Pattern.compile("\\b(class|interface|record)\\s+Dbt\\w*");

    @Test
    void backendContainsItsJavaModulesWithoutReverseAppDependencies() throws Exception {
        String parentPom = Files.readString(Path.of("..", "pom.xml"));
        String appPom = Files.readString(Path.of("pom.xml"));
        assertEquals(4, MODULE.matcher(parentPom).results().count());
        assertTrue(parentPom.contains("<module>metric-app</module>"));
        for (String module : new String[] {CORE, PERSISTENCE, ENGINE}) {
            Path pom = Path.of("..", module, "pom.xml");
            assertTrue(Files.isRegularFile(pom), module);
            assertTrue(parentPom.contains("<module>" + module + "</module>"), module);
            assertTrue(appPom.contains("<artifactId>" + module + "</artifactId>"), module);
            assertFalse(
                    Files.readString(pom).contains("<artifactId>metric-app</artifactId>"), module);
        }
        assertTrue(
                Files.isRegularFile(
                        Path.of(
                                "..",
                                "metric-core",
                                "src",
                                "main",
                                "java",
                                "com",
                                "metricplatform",
                                "engine",
                                "EngineClient.java")));
        assertTrue(
                Files.isRegularFile(
                        Path.of(
                                "..",
                                ENGINE,
                                "src",
                                "main",
                                "java",
                                "com",
                                "metricplatform",
                                "engine",
                                "dbt",
                                "git",
                                "DbtProjectReader.java")));
    }

    @Test
    void platformUsesEnginePortsAndKeepsDbtImplementationsInEngine() throws Exception {
        for (String module : List.of(CORE, PERSISTENCE, APP)) {
            Path root = Path.of("..", module, JAVA_ROOT);
            try (var files = Files.walk(root)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(JAVA_SUFFIX)).toList()) {
                    String source = Files.readString(file);
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    assertFalse(source.contains(LEGACY_PACKAGE), file.toString());
                    assertFalse(DBT_TYPE.matcher(source).find(), file.toString());
                    if (!APP.equals(module) || !ENGINE_ASSEMBLY.equals(relative)) {
                        assertFalse(source.contains(ENGINE_IMPLEMENTATION), file.toString());
                    }
                }
            }
        }
        String enginePom = Files.readString(Path.of("..", ENGINE, "pom.xml"));
        String persistencePom = Files.readString(Path.of("..", PERSISTENCE, "pom.xml"));
        String corePom = Files.readString(Path.of("..", CORE, "pom.xml"));
        assertFalse(enginePom.contains("<artifactId>metric-persistence</artifactId>"));
        assertFalse(persistencePom.contains("<artifactId>metric-engine</artifactId>"));
        assertFalse(corePom.contains("<artifactId>metric-engine</artifactId>"));
        assertFalse(corePom.contains("<artifactId>metric-persistence</artifactId>"));
    }
}
