package com.metricplatform.query;

import com.metricplatform.authorization.MetricAuthorizationService;
import com.metricplatform.authorization.MetricQueryAuditWriter;
import com.metricplatform.authorization.QueryPermission;
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
    private final MetricAuthorizationService authorization;
    private final MetricQueryAuditWriter audit;

    @GetMapping("/capabilities")
    public QueryDtos.CapabilitiesResponse capabilities() {
        return catalog.capabilities();
    }

    @GetMapping("/datasets/{id}/semantic-model")
    public Object model(
            @AuthenticationPrincipal PlatformPrincipal principal, @PathVariable UUID id) {
        authorization.requireDiscover(principal, id);
        return catalog.semanticModel(id);
    }

    @PostMapping("/semantic-objects:search")
    public List<QueryDtos.SemanticSearchResult> search(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody QueryDtos.SemanticSearchRequest request) {
        return catalog.search(request, authorization.discoverableDatasetIds(principal));
    }

    @PostMapping("/semantic-objects:resolve")
    public QueryDtos.SemanticResolveResponse resolve(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody QueryDtos.SemanticResolveRequest request) {
        authorization.requireResolve(principal, request);
        return catalog.resolve(request, authorization.discoverableDatasetIds(principal));
    }

    @PostMapping("/dimensions/{dimensionId}/members:search")
    public QueryDtos.DimensionMemberSearchResponse searchMembers(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @PathVariable UUID dimensionId,
            @Valid @RequestBody QueryDtos.DimensionMemberSearchRequest request)
            throws SQLException {
        authorization.requireMemberSearch(principal, dimensionId);
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
            datasetIds = authorization.authorizeQuery(principal, request, QueryPermission.VALIDATE);
            QueryDtos.ValidationResponse response = queries.validate(request);
            audit.write(
                    principal,
                    QueryPermission.VALIDATE,
                    auditId,
                    response.valid() ? "SUCCEEDED" : "INVALID",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            return response;
        } catch (RuntimeException failure) {
            audit.write(
                    principal,
                    QueryPermission.VALIDATE,
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
            datasetIds = authorization.authorizeQuery(principal, request, QueryPermission.EXECUTE);
            QueryDtos.QueryResponse response = queries.execute(request);
            audit.write(
                    principal,
                    QueryPermission.EXECUTE,
                    response.queryId() == null ? auditId : response.queryId(),
                    "SUCCEEDED",
                    request,
                    datasetIds,
                    elapsedMs(startedAt));
            return principal != null && principal.hasRole("METRIC_QUERY_CLIENT")
                    ? response.withoutSql()
                    : response;
        } catch (SQLException | RuntimeException failure) {
            audit.write(
                    principal,
                    QueryPermission.EXECUTE,
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
