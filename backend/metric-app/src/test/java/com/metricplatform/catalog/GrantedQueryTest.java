package com.metricplatform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.catalog.AssetGrantRepository.*;
import com.metricplatform.catalog.BuildContracts.*;
import com.metricplatform.catalog.api.BuildRequestParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class GrantedQueryTest {
    private final UUID build = UUID.randomUUID();
    private final BuildGateway gateway = mock(BuildGateway.class);
    private final List<Asset> assets =
            List.of(
                    asset("METRIC", "revenue", "", "", true),
                    asset("METRIC", "margin", "", "", false),
                    asset("DIMENSION", "orders", "region", "", false),
                    asset("DIMENSION", "customer", "region", "", true),
                    asset("DATASET", "orders", "", "fqn:pkg.orders", true));

    private Asset asset(
            String kind, String name, String dimension, String selector, boolean granted) {
        return new Asset(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                new Entry(kind, kind + name, dimension, name, selector),
                granted);
    }

    @Test
    void ambiguousCanonicalSelectorAndMetricNameAreNotReadable() {
        var duplicates = new ArrayList<>(assets);
        duplicates.add(asset("DATASET", "different", "", "fqn:pkg.orders", false));
        duplicates.add(asset("METRIC", "revenue", "", "", false));
        var access = new GrantedQuery(build, duplicates, gateway);
        assertTrue(
                access.readable().stream()
                        .noneMatch(
                                a ->
                                        a.entry().queryName().equals("revenue")
                                                || a.entry().kind().equals("DATASET")));
        assertThrows(
                SecurityException.class,
                () -> validate(access, "{\"mode\":\"PREVIEW\",\"selector\":\"orders\"}"));
    }

    @Test
    void ambiguousOptionIsHiddenBeforeFilteringByGrant() {
        var request = new AvailableDimensionsRequest(List.of("revenue"));
        when(gateway.availableDimensions(build, request))
                .thenReturn(
                        new AvailableDimensionsView(
                                build,
                                request.metrics(),
                                List.of(
                                        Map.of(
                                                "optionId",
                                                "same",
                                                "semanticModelName",
                                                "orders",
                                                "name",
                                                "region"),
                                        Map.of(
                                                "optionId",
                                                "same",
                                                "semanticModelName",
                                                "customer",
                                                "name",
                                                "region"))));
        var access = new GrantedQuery(build, assets, gateway);
        assertTrue(access.dimensions(request).dimensions().isEmpty());
        assertThrows(
                SecurityException.class,
                () ->
                        validate(
                                access,
                                "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"groupBy\":[{\"optionId\":\"same\"}]}"));
    }

    @Test
    void checksEveryExplicitResourceBeforeExecution() {
        var access = new GrantedQuery(build, assets, gateway);
        var request = new AvailableDimensionsRequest(List.of("revenue"));
        when(gateway.availableDimensions(build, request))
                .thenReturn(
                        new AvailableDimensionsView(
                                build,
                                request.metrics(),
                                List.of(
                                        Map.of(
                                                "optionId",
                                                "o",
                                                "semanticModelName",
                                                "orders",
                                                "name",
                                                "region"),
                                        Map.of(
                                                "optionId",
                                                "c",
                                                "semanticModelName",
                                                "customer",
                                                "name",
                                                "region"))));
        validate(access, "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"]}");
        validate(
                access,
                "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"groupBy\":[{\"optionId\":\"c\"}]}");
        validate(access, "{\"mode\":\"PREVIEW\",\"selector\":\"fqn:pkg.orders\"}");
        for (var input :
                List.of(
                        "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\",\"margin\"]}",
                        "{\"mode\":\"EXPLAIN\",\"metrics\":[\"revenue\"],\"groupBy\":[{\"optionId\":\"o\"}]}",
                        "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"filters\":[{\"optionId\":\"o\"}]}",
                        "{\"mode\":\"QUERY\",\"metrics\":[\"revenue\"],\"orderBy\":[{\"fieldId\":\"o\"}]}",
                        "{\"mode\":\"DIMENSION_VALUES\",\"metrics\":[\"revenue\"],\"dimensionOptionId\":\"o\"}",
                        "{\"mode\":\"PREVIEW\",\"selector\":\"*\"}"))
            assertThrows(SecurityException.class, () -> validate(access, input));
        verify(gateway, never()).query(any(), any());
    }

    @Test
    void metricGrantIncludesSystemTimeButNotModelDimensions() {
        var access = new GrantedQuery(build, assets, gateway);
        var request = new AvailableDimensionsRequest(List.of("revenue"));
        when(gateway.availableDimensions(build, request))
                .thenReturn(
                        new AvailableDimensionsView(
                                build,
                                request.metrics(),
                                List.of(
                                        Map.of("optionId", "day", "name", "metric_time"),
                                        Map.of(
                                                "optionId",
                                                "business",
                                                "name",
                                                "metric_time",
                                                "semanticModelName",
                                                "orders"))));
        validate(
                access,
                """
                {"mode":"QUERY","metrics":["revenue"],"groupBy":[{"optionId":"day","grain":"day"}],
                 "orderBy":[{"fieldId":"day","direction":"ASC"}],"startTime":"2026-11-01T00:00:00Z"}
                """);
        for (var id : List.of("business", "unknown"))
            assertThrows(
                    SecurityException.class,
                    () ->
                            validate(
                                    access,
                                    """
                    {"mode":"QUERY","metrics":["revenue"],"groupBy":[{"optionId":"%s"}]}
                    """
                                            .formatted(id)));
        assertThrows(
                SecurityException.class,
                () ->
                        validate(
                                access,
                                """
                {"mode":"QUERY","metrics":["margin"],"groupBy":[{"optionId":"day"}]}
                """));
    }

    @Test
    void checksBindingsInTheirOwnMetricContextAndAcceptsSortingByAuthorizedOutput() {
        var members = new ArrayList<>(assets);
        members.add(asset("METRIC", "cost", "", "", true));
        var access = new GrantedQuery(build, members, gateway);
        var revenue = new AvailableDimensionsRequest(List.of("revenue"));
        var cost = new AvailableDimensionsRequest(List.of("cost"));
        var mine = timeOption("local", "customer");
        var denied = timeOption("local", "orders");
        var revenueOnly = timeOption("revenue_only", "customer");
        when(gateway.availableDimensions(build, revenue))
                .thenReturn(
                        new AvailableDimensionsView(
                                build, revenue.metrics(), List.of(mine, revenueOnly)));
        when(gateway.availableDimensions(build, cost))
                .thenReturn(new AvailableDimensionsView(build, cost.metrics(), List.of(mine)));
        var query =
                """
                {"mode":"QUERY","metrics":["revenue","cost"],
                 "groupBy":[{"name":"period","bindings":{"revenue":{"optionId":"local","grain":"day"},
                                                         "cost":{"optionId":"%s","grain":"%s"}}},
                            {"name":"monthly","bindings":{"revenue":{"optionId":"local","grain":"month"},
                                                          "cost":{"optionId":"local","grain":"month"}}}],
                 "orderBy":[{"fieldId":"period","direction":"ASC"},{"fieldId":"monthly","direction":"DESC"}]}
                """;
        validate(access, query.formatted("local", "day"));
        verify(gateway).availableDimensions(build, revenue);
        verify(gateway).availableDimensions(build, cost);
        verifyNoMoreInteractions(gateway);
        // 两个指标都有同名选项时仍分别查授权；一个指标的选项或粒度不能借给另一个指标。
        assertThrows(
                SecurityException.class,
                () -> validate(access, query.formatted("revenue_only", "day")));
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(access, query.formatted("local", "year")));
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(access, query.formatted("local", "month")));
        when(gateway.availableDimensions(build, cost))
                .thenReturn(new AvailableDimensionsView(build, cost.metrics(), List.of(denied)));
        assertThrows(
                SecurityException.class, () -> validate(access, query.formatted("local", "day")));
    }

    private Map<String, Object> timeOption(String id, String model) {
        return Map.of(
                "optionId",
                id,
                "name",
                "region",
                "semanticModelName",
                model,
                "dimensionType",
                "time",
                "granularities",
                List.of("day", "month"));
    }

    private void validate(GrantedQuery access, String input) {
        access.check(BuildRequestParser.parse(input, QueryRequest.class));
    }
}
