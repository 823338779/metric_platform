package com.metricplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.metricplatform.persistence.entity.BaseAuditEntity;
import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LombokBoilerplateArchitectureTest {
    private static final Pattern MECHANICAL_GETTER =
            Pattern.compile(
                    "(?ms)\\b(get|is)[A-Z]\\w*\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+(?:this\\.)?\\w+\\s*;\\s*}");
    private static final Pattern MECHANICAL_SETTER =
            Pattern.compile(
                    "(?ms)\\bset[A-Z]\\w*\\s*\\([^)]*\\)\\s*\\{\\s*(?:this\\.)?\\w+\\s*=\\s*\\w+\\s*;\\s*}");
    private static final Pattern MANUAL_LOGGER =
            Pattern.compile("LoggerFactory\\.getLogger\\s*\\(");

    private static final List<String> ENTITY_FILES =
            List.of(
                    "BaseAuditEntity.java",
                    "ApiClientEntity.java",
                    "AuditEventEntity.java",
                    "DataSourceEntity.java",
                    "DatasetEntity.java",
                    "DatasetFieldEntity.java",
                    "DatasetRelationshipEntity.java",
                    "DatasetRelationshipFieldPairEntity.java",
                    "DimensionEntity.java",
                    "DimensionTimeGranularityEntity.java",
                    "MetricEntity.java",
                    "PlatformUserEntity.java",
                    "SemanticAliasEntity.java");

    private static final Map<String, String> REQUIRED_CONSTRUCTORS =
            Map.ofEntries(
                    entry("persistence/AuditMetaObjectHandler.java", "AuditMetaObjectHandler"),
                    entry("metadata/MetadataController.java", "MetadataController"),
                    entry("metadata/MetadataService.java", "MetadataService"),
                    entry("metadata/dataset/DatasetController.java", "DatasetController"),
                    entry("metadata/dataset/DatasetService.java", "DatasetService"),
                    entry("metadata/dataset/JdbcSourceMetadataInspector.java", "JdbcSourceMetadataInspector"),
                    entry("metadata/dataset/SchemaDriftService.java", "SchemaDriftService"),
                    entry("metadata/relationship/DatasetRelationshipController.java", "DatasetRelationshipController"),
                    entry("metadata/relationship/DatasetRelationshipService.java", "DatasetRelationshipService"),
                    entry("query/QueryController.java", "QueryController"),
                    entry("query/QueryService.java", "QueryService"),
                    entry("query/SemanticCatalogReader.java", "SemanticCatalogReader"),
                    entry("query/compiler/SemanticSqlCompiler.java", "SemanticSqlCompiler"),
                    entry("query/planner/QueryPlanner.java", "QueryPlanner"),
                    entry("semantic/SemanticController.java", "SemanticController"),
                    entry("semantic/SemanticDefinitionService.java", "SemanticDefinitionService"),
                    entry("source/DataSourceConnectionController.java", "DataSourceConnectionController"),
                    entry("source/DataSourceManagementService.java", "DataSourceManagementService"),
                    entry("source/JdbcConnectionService.java", "JdbcConnectionService"),
                    entry("source/JdbcMetadataService.java", "JdbcMetadataService"),
                    entry("source/SourceCatalogController.java", "SourceCatalogController"));

    @Test
    void usesLombokForMechanicalAccessorsConstructorsAndLoggers() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("basedir"), "src/main/java");
        Path entityRoot = sourceRoot.resolve("com/metricplatform/persistence/entity");
        List<String> findings = new ArrayList<>();

        for (String fileName : ENTITY_FILES) {
            String source = Files.readString(entityRoot.resolve(fileName));
            addFinding(findings, fileName, "mechanical getter", MECHANICAL_GETTER, source);
            addFinding(findings, fileName, "mechanical setter", MECHANICAL_SETTER, source);
        }

        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                addFinding(
                        findings,
                        sourceRoot.relativize(file).toString(),
                        "manual logger",
                        MANUAL_LOGGER,
                        Files.readString(file));
            }
        }

        for (var entry : REQUIRED_CONSTRUCTORS.entrySet()) {
            String source = Files.readString(sourceRoot.resolve("com/metricplatform").resolve(entry.getKey()));
            if (!source.contains("@RequiredArgsConstructor")) {
                findings.add(entry.getKey() + ": missing @RequiredArgsConstructor");
            }
            Pattern constructor =
                    Pattern.compile("(?m)^\\s*(?:public|protected|private)?\\s*" + entry.getValue() + "\\s*\\(");
            addFinding(findings, entry.getKey(), "manual dependency constructor", constructor, source);
        }

        assertThat(findings).isEmpty();
    }

    @Test
    void generatedAccessorsKeepTheExistingBeanApi() throws NoSuchMethodException {
        assertThat(DatasetEntity.class.getMethod("getId")).isNotNull();
        assertThat(DatasetFieldEntity.class.getMethod("isSelected")).isNotNull();
        assertThat(DataSourceEntity.class.getMethod("setVersion", long.class)).isNotNull();
        assertThat(BaseAuditEntity.class.getMethod("setDeleted", boolean.class)).isNotNull();
        assertThat(DataSourceEntity.class.getMethod("setSecretReference", String.class)).isNotNull();
    }

    private static Map.Entry<String, String> entry(String file, String type) {
        return Map.entry(file, type);
    }

    private static void addFinding(
            List<String> findings, String file, String description, Pattern pattern, String source) {
        if (pattern.matcher(source).find()) {
            findings.add(file + ": " + description);
        }
    }
}
