package com.metricplatform.catalog;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{\"optionId\":\"s\"}}}",
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{},\"cost\":{\"optionId\":\"c\"}}}",
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{\"optionId\":1},\"cost\":{\"optionId\":\"c\"}}}",
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{\"optionId\":\"s\"},\"cost\":null}}",
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{\"optionId\":\"s\"},\"extra\":{\"optionId\":\"e\"}}}",
                "{\"name\":\"channel\",\"bindings\":{\"sales\":{},\"sales\":{\"optionId\":\"s\"},\"cost\":{\"optionId\":\"c\"}}}",
                "{\"name\":\"sales\",\"bindings\":{\"sales\":{\"optionId\":\"s\"},\"cost\":{\"optionId\":\"c\"}}}",
                "{\"name\":\"1channel\",\"bindings\":{\"sales\":{\"optionId\":\"s\"},\"cost\":{\"optionId\":\"c\"}}}",
                "{\"optionId\":null,\"name\":\"channel\",\"bindings\":{\"sales\":{\"optionId\":\"s\"},\"cost\":{\"optionId\":\"c\"}}}"
            })
    void rejectsMalformedBoundGroups(String group) {
        var query =
                """
                {"mode":"QUERY","metrics":["sales","cost"],"groupBy":[%s]}
                """
                        .formatted(group);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BuildRequestParser.parse(query, QueryRequest.class));
    }

    @Test
    void preservesBothGroupWireFormatsAndRejectsDuplicateOutputOrMetricNames() throws Exception {
        var mapper = new ObjectMapper();
        var group =
                """
                {"name":"channel","bindings":{"sales":{"optionId":"s","grain":null}}}
                """;
        var query =
                """
                {"mode":"QUERY","metrics":[%s],"groupBy":[%s]}
                """;
        var valid =
                query.formatted("\"sales\"", "{\"optionId\":\"region\",\"grain\":null}," + group);
        var parsed = BuildRequestParser.parse(valid, QueryRequest.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(parsed)))
                .isEqualTo(mapper.readTree(valid));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                BuildRequestParser.parse(
                                        query.formatted("\"sales\"", group + "," + group),
                                        QueryRequest.class));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                BuildRequestParser.parse(
                                        query.formatted("\"sales\",\"sales\"", group),
                                        QueryRequest.class));
    }
}
