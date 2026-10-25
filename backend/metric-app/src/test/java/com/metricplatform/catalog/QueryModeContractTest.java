package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;

import com.metricplatform.catalog.BuildContracts.QueryRequest;
import com.metricplatform.catalog.api.BuildRequestParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryModeContractTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"mode\":\"PREVIEW\",\"selector\":\"orders\",\"metrics\":null}",
                "{\"mode\":\"PREVIEW\",\"selector\":\"orders\",\"groupBy\":[]}",
                "{\"mode\":\"DIMENSION_VALUES\",\"metrics\":[\"revenue\"],\"dimensionOptionId\":\"region\",\"orderBy\":[]}",
                "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"selector\":null}",
                "{\"mode\":\"QUERY\",\"metricResourceIds\":[\"revenue\"]}",
                "{\"mode\":\"PREVIEW\",\"selector\":\"   \"}"
            })
    void rejectsFieldsOutsideTheSelectedModeEvenWhenEmptyOrNull(String body) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BuildRequestParser.parse(body, QueryRequest.class));
    }

    @Test
    void preservesPreviewSelectorWithoutInventingMetricFields() {
        var request =
                BuildRequestParser.parse(
                        "{\"mode\":\"PREVIEW\",\"selector\":\"orders\",\"limit\":10}",
                        QueryRequest.class);
        assertThat(request.selector()).isEqualTo("orders");
        assertThat(request.metrics()).isNull();
    }
}
