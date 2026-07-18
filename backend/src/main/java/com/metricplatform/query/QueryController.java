package com.metricplatform.query;

import com.metricplatform.security.PlatformPrincipal;
import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QueryController {
    private final SemanticCatalogService catalog;
    private final DimensionMemberService members;
    private final QueryService queries;

    @GetMapping("/capabilities")
    public QueryDtos.CapabilitiesResponse capabilities() {
        return catalog.capabilities();
    }

    @GetMapping("/datasets/{id}/semantic-model")
    public Object model(@PathVariable UUID id) {
        return catalog.semanticModel(id);
    }

    @PostMapping("/semantic-objects:search")
    public List<QueryDtos.SemanticSearchResult> search(
            @Valid @RequestBody QueryDtos.SemanticSearchRequest request) {
        return catalog.search(request);
    }

    @PostMapping("/semantic-objects:resolve")
    public QueryDtos.SemanticResolveResponse resolve(
            @Valid @RequestBody QueryDtos.SemanticResolveRequest request) {
        return catalog.resolve(request);
    }

    @PostMapping("/dimensions/{dimensionId}/members:search")
    public QueryDtos.DimensionMemberSearchResponse searchMembers(
            @PathVariable UUID dimensionId,
            @Valid @RequestBody QueryDtos.DimensionMemberSearchRequest request) throws SQLException {
        return members.search(dimensionId, request);
    }

    @PostMapping("/queries:validate")
    public QueryDtos.ValidationResponse validate(
            @Valid @RequestBody QueryDtos.QueryRequest request) {
        return queries.validate(request);
    }

    @PostMapping("/queries")
    public QueryDtos.QueryResponse execute(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody QueryDtos.QueryRequest request)
            throws SQLException {
        QueryDtos.QueryResponse response = queries.execute(request);
        return principal != null && "METRIC_QUERY_CLIENT".equals(principal.role())
                ? response.withoutSql()
                : response;
    }
}
