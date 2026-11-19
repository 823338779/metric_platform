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

    private void validate(GrantedQuery access, String input) {
        access.check(BuildRequestParser.parse(input, QueryRequest.class));
    }
}
