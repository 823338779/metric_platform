package com.metricplatform.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerTest {
    private static final String ROOT = "/api/v3/dbt",
            AUTH = "Authorization",
            TOKEN = "Bearer query-test";
    @Autowired MockMvc mvc;
    @MockitoBean BuildGateway gateway;
    @MockitoBean PlatformAuthenticationService authentication;
    private final UUID build = UUID.randomUUID(), query = UUID.randomUUID();

    @BeforeEach
    void prepare() {
        when(authentication.authenticate("query-test"))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "user", "User", null, 1));
        when(gateway.getBuild(build))
                .thenReturn(
                        new BuildView(
                                build,
                                "repo",
                                "a",
                                null,
                                null,
                                Environment.PREVIEW,
                                "binding",
                                "1",
                                "1",
                                BuildStatus.SUCCEEDED,
                                "DONE",
                                null,
                                null,
                                null,
                                false,
                                true,
                                true,
                                null,
                                null,
                                null));
    }

    @ParameterizedTest
    @EnumSource(QueryMode.class)
    void allQueryModesRemainFixedToRequestedBuild(QueryMode mode) throws Exception {
        var request =
                new QueryRequest(
                        "request-" + mode,
                        mode,
                        mode == QueryMode.PREVIEW ? List.of() : List.of("metric.revenue"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        100,
                        mode == QueryMode.PREVIEW ? "model.orders" : null,
                        mode == QueryMode.DIMENSION_VALUES ? "option-region" : null);
        var view =
                new QueryView(
                        query,
                        build,
                        BuildStatus.QUEUED,
                        mode,
                        "a".repeat(40),
                        "Asia/Shanghai",
                        Map.of(),
                        "metricflow_granularity_alignment",
                        false,
                        null);
        when(gateway.query(build, request)).thenReturn(view);
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value(build.toString()))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.mode").value(mode.name()));
        verify(gateway).getBuild(build);
        verify(gateway).query(build, request);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void resultPaginationPreservesExactValuesAndExplainSql() throws Exception {
        var result =
                new ResultPage(
                        query,
                        build,
                        BuildStatus.SUCCEEDED,
                        QueryMode.EXPLAIN,
                        "a".repeat(40),
                        "Asia/Shanghai",
                        Map.of("startTime", "2026-10-10T00:00:00.123456"),
                        "metricflow_granularity_alignment",
                        true,
                        null,
                        "select precise_value from fixed_build",
                        List.of(Map.of("name", "precise_value", "type", "DECIMAL")),
                        List.of(List.of("9007199254740993.123456789")),
                        5,
                        1,
                        6,
                        20,
                        true);
        when(gateway.queryResults(query, 5, 1)).thenReturn(result);
        mvc.perform(
                        get(ROOT + "/queries/" + query + "/results")
                                .param("offset", "5")
                                .param("limit", "1")
                                .header(AUTH, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][0]").value("9007199254740993.123456789"))
                .andExpect(jsonPath("$.sql").value("select precise_value from fixed_build"))
                .andExpect(jsonPath("$.nextOffset").value(6))
                .andExpect(jsonPath("$.availableRows").value(20))
                .andExpect(jsonPath("$.resultTruncated").value(true))
                .andExpect(
                        jsonPath("$.normalizedTimeRange.startTime")
                                .value("2026-10-10T00:00:00.123456"));
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "0,0", "0,201", "0,1000"})
    void rejectsResultPaginationOutsideServiceContract(int offset, int limit) throws Exception {
        mvc.perform(
                        get(ROOT + "/queries/" + query + "/results")
                                .param("offset", Integer.toString(offset))
                                .param("limit", Integer.toString(limit))
                                .header(AUTH, TOKEN))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(gateway);
    }

    @Test
    void acceptsLargestResultPageAndArbitraryValidOffset() throws Exception {
        mvc.perform(
                        get(ROOT + "/queries/" + query + "/results")
                                .param("offset", "1000")
                                .param("limit", "200")
                                .header(AUTH, TOKEN))
                .andExpect(status().isOk());
        verify(gateway).queryResults(query, 1000, 200);
    }

    @Test
    void queryRowLimitRemainsIndependentOfResultPageSize() throws Exception {
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new QueryView(
                                query,
                                build,
                                BuildStatus.QUEUED,
                                QueryMode.QUERY,
                                "a".repeat(40),
                                "Asia/Shanghai",
                                Map.of(),
                                "metricflow_granularity_alignment",
                                false,
                                null));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(
                                        """
                                        {"idempotencyKey":"row-limit-1000","mode":"QUERY",
                                        "metricResourceIds":["metric.revenue"],"limit":1000}
                                        """))
                .andExpect(status().isAccepted());
        verify(gateway).query(eq(build), argThat(request -> request.limit() == 1000));
    }

    @Test
    void filterDecimalAndBigintAreNotRoundedByBffParsing() throws Exception {
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new QueryView(
                                query,
                                build,
                                BuildStatus.QUEUED,
                                QueryMode.QUERY,
                                null,
                                "Asia/Shanghai",
                                Map.of(),
                                "metricflow_granularity_alignment",
                                false,
                                null));
        var body =
                "{\"idempotencyKey\":\"precision\",\"mode\":\"QUERY\",\"metricResourceIds\":[\"metric.revenue\"],\"filters\":[{\"optionId\":\"amount\",\"operator\":\"=\",\"value\":1234567890.123456789},{\"optionId\":\"identifier\",\"operator\":\"=\",\"value\":900719925474099312345}]}";
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isAccepted());
        var captured = org.mockito.ArgumentCaptor.forClass(QueryRequest.class);
        verify(gateway).query(eq(build), captured.capture());
        assertThat(captured.getValue().filters().get(0).value().toString())
                .isEqualTo("1234567890.123456789");
        assertThat(captured.getValue().filters().get(1).value().toString())
                .isEqualTo("900719925474099312345");
    }

    @Test
    void asyncFailureAndUnavailableReasonRemainStructured() throws Exception {
        var error =
                new ErrorView(
                        "INVALID_DIMENSION_OPTION", "selection mismatch", false, "QUERY", build);
        when(gateway.queryStatus(query))
                .thenReturn(
                        new QueryView(
                                query,
                                build,
                                BuildStatus.FAILED,
                                QueryMode.QUERY,
                                null,
                                "Asia/Shanghai",
                                Map.of(),
                                "metricflow_granularity_alignment",
                                false,
                                error));
        mvc.perform(get(ROOT + "/queries/" + query + "/status").header(AUTH, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value("INVALID_DIMENSION_OPTION"))
                .andExpect(jsonPath("$.error.buildId").value(build.toString()));
        when(gateway.queryStatus(query))
                .thenThrow(
                        new BuildGateway.Failure(
                                503,
                                new ErrorView(
                                        "EXECUTION_ENVIRONMENT_UNAVAILABLE",
                                        "unavailable",
                                        true,
                                        "QUERY",
                                        build)));
        mvc.perform(get(ROOT + "/queries/" + query + "/status").header(AUTH, TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXECUTION_ENVIRONMENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
