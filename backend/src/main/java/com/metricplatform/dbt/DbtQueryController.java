package com.metricplatform.dbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.dbt.DbtQueryDtos.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** dbt 查询只接受固定发布版本和类型化参数；不提供任意 SQL 入口。 */
@RestController
@ConditionalOnProperty(
        prefix = "metric-platform.dbt-runtime",
        name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@RequestMapping(DbtCatalogController.ROOT)
public class DbtQueryController {
    private static final String QUERIES = "/queries",
            EXPLAIN = "/queries:explain",
            QUERY = "/queries/{queryId}",
            PREVIEW = "/datasets/{resourceId}:preview";
    private final DbtQueryService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping(QUERIES)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuerySubmission query(@PathVariable String projectId, @RequestBody String body) {
        TypedQuery request = typed(body);
        return service.submit(projectId, request.releaseId(), QueryMode.QUERY, request);
    }

    @PostMapping(EXPLAIN)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuerySubmission explain(@PathVariable String projectId, @RequestBody String body) {
        TypedQuery request = typed(body);
        return service.submit(projectId, request.releaseId(), QueryMode.EXPLAIN, request);
    }

    @PostMapping(PREVIEW)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuerySubmission preview(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestBody String body) {
        JsonNode node = parse(body);
        rejectUnsupported(node, true);
        return service.preview(
                projectId, resourceId, mapper.convertValue(node, PreviewRequest.class));
    }

    @GetMapping(QUERY)
    public QueryStatus get(@PathVariable String projectId, @PathVariable UUID queryId) {
        return service.get(projectId, queryId);
    }

    private TypedQuery typed(String body) {
        JsonNode node = parse(body);
        rejectUnsupported(node, false);
        return mapper.convertValue(node, TypedQuery.class);
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("查询参数无效");
        }
    }

    private void rejectUnsupported(JsonNode body, boolean preview) {
        if (body == null || !body.isObject() || body.has("sql"))
            throw new IllegalArgumentException("查询参数无效");
        var names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals("releaseId")
                    && !name.equals("limit")
                    && (preview
                            || !name.equals("metrics")
                                    && !name.equals("groupBy")
                                    && !name.equals("filters")))
                throw new IllegalArgumentException("查询参数无效");
        }
    }
}
