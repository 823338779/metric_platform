package com.metricplatform.engine.dbt.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DbtArtifactPresenterTest {
    private static final Map<String, String> SCHEMAS =
            Map.of(
                    "manifest", "https://schemas.getdbt.com/dbt/manifest/v12.json",
                    "semanticManifest", "dbt-metricflow/0.15.0",
                    "catalog", "https://schemas.getdbt.com/dbt/catalog/v1.json");
    private final DbtArtifactPresenter presenter = new DbtArtifactPresenter();

    @Test
    void metricDimensionSemanticModelTableAndViewHaveTypedSections() {
        UUID release = UUID.randomUUID();
        for (String kind :
                new String[] {"METRIC", "DIMENSION", "SEMANTIC_MODEL", "TABLE", "VIEW"}) {
            var result =
                    presenter.present(
                            release,
                            "resource",
                            kind,
                            "Revenue",
                            "{\"type\":\"simple\",\"dimensions\":[{\"name\":\"region\"}],"
                                    + "\"_platformCatalog\":{\"relation\":\"warehouse.orders\",\"columns\":{}}}",
                            true,
                            SCHEMAS);
            assertThat(result.sections()).isNotEmpty();
            assertThat(result.nativeDetails().has("_platformCatalog")).isFalse();
            assertThat(result.queryable()).isTrue();
        }
    }

    @Test
    void unknownResourceShowsNativeDetailsWithoutQueryCapability() {
        var result =
                presenter.present(
                        UUID.randomUUID(),
                        "native",
                        "EXPOSURE",
                        "Owner",
                        "{\"owner\":\"team\"}",
                        true,
                        SCHEMAS);
        assertThat(result.queryable()).isFalse();
        assertThat(result.nativeDetails().path("owner").asText()).isEqualTo("team");
    }

    @Test
    void unsupportedArtifactVersionFailsClosed() {
        assertThatThrownBy(
                        () ->
                                presenter.present(
                                        UUID.randomUUID(),
                                        "metric",
                                        "METRIC",
                                        "Revenue",
                                        "{}",
                                        true,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("产物版本");
    }
}
