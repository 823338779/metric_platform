package com.metricplatform.query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.BuildGateway;
import com.metricplatform.environment.EnvironmentBuildGateway;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.*;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerTest {
    private static final java.util.UUID ENVIRONMENT_ID =
            java.util.UUID.fromString("12345678-1234-4234-8234-000000000001");

    private static final String ROOT = "/api/dbt",
            AUTH = "Authorization",
            TOKEN = "Bearer query-test";
    @Autowired MockMvc mvc;
    @MockitoBean BuildGateway gateway;
    @MockitoBean EnvironmentBuildGateway environmentBuilds;
    @MockitoBean PlatformAuthenticationService authentication;
    private final UUID build = UUID.randomUUID(), query = UUID.randomUUID();

    @Test
    void explainRetainsNullableCommitIdentityWithoutTabularFields() throws Exception {
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.EXPLAIN,
                                null,
                                "UTC",
                                Map.of(),
                                "select 1",
                                null,
                                null,
                                null));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content("{\"mode\":\"EXPLAIN\",\"metrics\":[\"revenue\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").hasJsonPath())
                .andExpect(jsonPath("$.commitSha").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.rows").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.columns").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.resultTruncated").doesNotHaveJsonPath());
    }

    @Test
    void snapshotBuildQueryDoesNotReadLegacyMetadata() throws Exception {
        when(gateway.getBuild(build))
                .thenThrow(
                        new BuildGateway.Failure(
                                409,
                                new ErrorView(
                                        "PROTOCOL_VERSION_MISMATCH", "use v4", false, null, null)));
        when(environmentBuilds.getBuild(build))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"buildId\":\"" + build + "\",\"queryAvailable\":true}"));
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.EXPLAIN,
                                "a".repeat(40),
                                "Asia/Shanghai",
                                Map.of(),
                                "select 1",
                                List.of(),
                                List.of(),
                                false));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content("{\"mode\":\"EXPLAIN\",\"metrics\":[\"revenue\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()));
        verify(environmentBuilds).getBuild(build);
        verify(gateway, never()).getBuild(any());
    }

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
                                new com.metricplatform.environment.EnvironmentContracts
                                        .ExecutionSnapshot(
                                        ENVIRONMENT_ID,
                                        1,
                                        new com.metricplatform.environment.EnvironmentContracts
                                                .ConnectionTarget(
                                                "starrocks", "warehouse", "localhost", 9030, null),
                                        "warehouse-test",
                                        "default_catalog",
                                        "analytics",
                                        2,
                                        "Asia/Shanghai"),
                                "1",
                                BuildStatus.SUCCEEDED,
                                "DONE",
                                null,
                                null,
                                null,
                                true,
                                null,
                                null));
    }

    @Test
    void availableDimensionsReturnCompletedViewWithoutTaskReceipt() throws Exception {
        var metrics = List.of("revenue");
        when(gateway.availableDimensions(build, new AvailableDimensionsRequest(metrics)))
                .thenReturn(new AvailableDimensionsView(build, metrics, List.of()));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/available-dimensions")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content("{\"metrics\":[\"revenue\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()))
                .andExpect(jsonPath("$.dimensions").isArray())
                .andExpect(jsonPath("$.optionsTaskId").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());
        verify(gateway).availableDimensions(build, new AvailableDimensionsRequest(metrics));
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void removedStatusRoutesAreNotExposed() throws Exception {
        mvc.perform(get(ROOT + "/query-options/" + query).header(AUTH, TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/queries/" + query + "/status").header(AUTH, TOKEN))
                .andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @EnumSource(QueryMode.class)
    void allQueryModesRemainFixedToRequestedBuild(QueryMode mode) throws Exception {
        var request =
                new QueryRequest(
                        mode,
                        mode == QueryMode.PREVIEW ? null : List.of("revenue"),
                        mode == QueryMode.QUERY || mode == QueryMode.EXPLAIN ? List.of() : null,
                        mode == QueryMode.PREVIEW ? null : List.of(),
                        null,
                        null,
                        mode == QueryMode.QUERY || mode == QueryMode.EXPLAIN ? List.of() : null,
                        100,
                        mode == QueryMode.PREVIEW ? "model.orders" : null,
                        mode == QueryMode.DIMENSION_VALUES ? "option-region" : null);
        var view =
                new ResultPage(
                        build,
                        mode,
                        "a".repeat(40),
                        "Asia/Shanghai",
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        false);
        when(gateway.query(build, request)).thenReturn(view);
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value(build.toString()))
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.queryId").doesNotExist())
                .andExpect(jsonPath("$.mode").value(mode.name()));
        verify(environmentBuilds).getBuild(build);
        verify(gateway).query(build, request);
        verifyNoMoreInteractions(gateway);
        verifyNoMoreInteractions(environmentBuilds);
    }

    @Test
    void resultPaginationPreservesExactValuesAndExplainSql() throws Exception {
        var result =
                new ResultPage(
                        build,
                        QueryMode.EXPLAIN,
                        "a".repeat(40),
                        "Asia/Shanghai",
                        Map.of("startTime", "2026-10-10T00:00:00.123456"),
                        "select precise_value from fixed_build",
                        List.of(Map.of("name", "precise_value", "type", "DECIMAL")),
                        List.of(List.of("9007199254740993.123456789")),
                        true);
        when(gateway.query(eq(build), any())).thenReturn(result);
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .contentType("application/json")
                                .content("{\"mode\":\"EXPLAIN\",\"metrics\":[\"revenue\"]}")
                                .header(AUTH, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][0]").value("9007199254740993.123456789"))
                .andExpect(jsonPath("$.sql").value("select precise_value from fixed_build"))
                .andExpect(jsonPath("$.nextOffset").doesNotExist())
                .andExpect(jsonPath("$.availableRows").doesNotExist())
                .andExpect(jsonPath("$.resultTruncated").value(true))
                .andExpect(
                        jsonPath("$.normalizedTimeRange.startTime")
                                .value("2026-10-10T00:00:00.123456"));
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "0,0", "0,201", "0,1000", "1000,200"})
    void removedResultPaginationIsNotExposed(int offset, int limit) throws Exception {
        mvc.perform(
                        get(ROOT + "/queries/" + query + "/results")
                                .param("offset", Integer.toString(offset))
                                .param("limit", Integer.toString(limit))
                                .header(AUTH, TOKEN))
                .andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
    }

    @Test
    void queryRowLimitRemainsIndependentOfResultPageSize() throws Exception {
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.QUERY,
                                "a".repeat(40),
                                "Asia/Shanghai",
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                false));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(
                                        """
                                        {"mode":"QUERY",
                                        "metrics":["revenue"],"limit":1000}
                                        """))
                .andExpect(status().isOk());
        verify(gateway).query(eq(build), argThat(request -> request.limit() == 1000));
    }

    @Test
    void filterDecimalAndBigintAreNotRoundedByBffParsing() throws Exception {
        when(gateway.query(eq(build), any()))
                .thenReturn(
                        new ResultPage(
                                build,
                                QueryMode.QUERY,
                                null,
                                "Asia/Shanghai",
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                false));
        var body =
                "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"filters\":[{\"optionId\":\"amount\",\"operator\":\"=\",\"value\":1234567890.123456789},{\"optionId\":\"identifier\",\"operator\":\"=\",\"value\":900719925474099312345}]}";
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        var captured = org.mockito.ArgumentCaptor.forClass(QueryRequest.class);
        verify(gateway).query(eq(build), captured.capture());
        assertThat(captured.getValue().filters().get(0).value().toString())
                .isEqualTo("1234567890.123456789");
        assertThat(captured.getValue().filters().get(1).value().toString())
                .isEqualTo("900719925474099312345");
    }

    @ParameterizedTest
    @CsvSource({"422,INVALID_DIMENSION_OPTION,false", "503,EXECUTION_ENVIRONMENT_UNAVAILABLE,true"})
    void synchronousFailureRemainsStructured(int status, String code, boolean retryable)
            throws Exception {
        when(gateway.query(eq(build), any()))
                .thenThrow(
                        new BuildGateway.Failure(
                                status,
                                new ErrorView(code, "diagnostic", retryable, "QUERY", build)));
        mvc.perform(
                        post(ROOT + "/builds/" + build + "/queries")
                                .header(AUTH, TOKEN)
                                .contentType("application/json")
                                .content("{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"]}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.buildId").value(build.toString()))
                .andExpect(jsonPath("$.retryable").value(retryable));
    }
}
