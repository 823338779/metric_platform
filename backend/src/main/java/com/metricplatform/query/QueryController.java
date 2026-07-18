package com.metricplatform.query;

import com.metricplatform.security.PlatformPrincipal;
import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QueryController {
    private final SemanticCatalogService catalog;
    private final DimensionMemberService members;
    private final QueryService queries;
    private final QueryConnectivityService connectivity;
    private final QueryDatasetResolver datasetResolver;
    private final MetricQueryAuditWriter audit;

    @GetMapping("/capabilities")
    public QueryDtos.CapabilitiesResponse capabilities() {
        return catalog.capabilities();
    }

    @GetMapping("/query-connectivity")
    public QueryDtos.QueryConnectivityResponse connectivity() {
        return connectivity.connectivity();
    }

    @GetMapping("/datasets/{id}/semantic-model")
    public Object model(@PathVariable UUID id) {
        return catalog.semanticModel(id);
    }

    @PostMapping("/semantic-objects:search")
    public List<QueryDtos.SemanticSearchResult> search(
            @Valid @RequestBody QueryDtos.SemanticSearchRequest request) {
        return catalog.search(request, null);
    }

    @PostMapping("/semantic-objects:resolve")
    public QueryDtos.SemanticResolveResponse resolve(
            @Valid @RequestBody QueryDtos.SemanticResolveRequest request) {
        datasetResolver.requireResolve(request);
        return catalog.resolve(request, null);
    }

    @PostMapping("/dimensions/{dimensionId}/members:search")
    public QueryDtos.DimensionMemberSearchResponse searchMembers(
            @PathVariable UUID dimensionId,
            @Valid @RequestBody QueryDtos.DimensionMemberSearchRequest request)
            throws SQLException {
        return members.search(dimensionId, request);
    }

    @PostMapping("/queries:validate")
    public QueryDtos.ValidationResponse validate(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody QueryDtos.QueryRequest request) {
        long startedAt = System.nanoTime();
        UUID auditId = UUID.randomUUID();
        Set<UUID> datasetIds = Set.of();
        try {
            datasetIds = datasetResolver.datasetIds(request);
            QueryDtos.ValidationResponse response = queries.validate(request);
            audit.write(
                    principal,
                    QueryOperation.VALIDATE,
                    auditId,
                    response.valid() ? "SUCCEEDED" : "INVALID",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            return response;
        } catch (RuntimeException failure) {
            audit.write(
                    principal,
                    QueryOperation.VALIDATE,
                    auditId,
                    "FAILED",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            throw failure;
        }
    }

    @PostMapping("/queries")
    public QueryDtos.QueryResponse execute(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody QueryDtos.QueryRequest request)
            throws SQLException {
        long startedAt = System.nanoTime();
        UUID auditId = UUID.randomUUID();
        Set<UUID> datasetIds = Set.of();
        try {
            datasetIds = datasetResolver.datasetIds(request);
            QueryDtos.QueryResponse response = queries.execute(request);
            audit.write(
                    principal,
                    QueryOperation.EXECUTE,
                    response.queryId() == null ? auditId : response.queryId(),
                    "SUCCEEDED",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            return response;
        } catch (SQLException | RuntimeException failure) {
            audit.write(
                    principal,
                    QueryOperation.EXECUTE,
                    auditId,
                    "FAILED",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            throw failure;
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
