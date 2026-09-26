package com.metricplatform.dbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metricplatform.dbt.DbtQueryDtos.*;
import com.metricplatform.dbt.DbtRuntimeDtos.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 先持久固定 release 引用，再向运行服务提交类型化任务。 */
@Service
@ConditionalOnProperty(
        prefix = "metric-platform.dbt-runtime",
        name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DbtQueryService {
    private static final String PENDING = "PENDING", FAILED = "FAILED", COMPLETED = "COMPLETED";
    private static final String RUNTIME_READY = "READY";
    private static final java.util.Set<String> FILTER_OPERATORS =
            java.util.Set.of("=", "!=", ">", ">=", "<", "<=", "IN");
    private static final int MAX_LIMIT = 10000;
    private static final int RECONCILE_BATCH = 100;
    private final DbtCatalogService catalog;
    private final DbtQueryRepository queries;
    private final DbtRuntimeClient runtime;
    private final SemanticReleaseMapper releases;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 查询无需由浏览器持续轮询；重启后也按幂等键恢复并释放终态引用。 */
    @Scheduled(fixedDelayString = "${metric-platform.dbt-query.poll-ms:30000}")
    public void reconcilePending() {
        for (DbtQueryRepository.PendingQuery pending : queries.pending(RECONCILE_BATCH)) {
            try {
                get(pending.projectId(), pending.queryId());
            } catch (DbtRuntimeClient.ServiceFailure failure) {
                log.warn(
                        "dbt query recovery deferred for {} with status {}",
                        pending.queryId(),
                        failure.status());
            }
        }
    }

    public QuerySubmission submit(
            String projectId, UUID releaseId, QueryMode mode, TypedQuery request) {
        if (releaseId == null
                || request == null
                || request.limit() != null && (request.limit() < 1 || request.limit() > MAX_LIMIT)
                || mode != QueryMode.PREVIEW
                        && (request.metrics() == null || request.metrics().isEmpty()))
            throw new IllegalArgumentException("查询参数无效");
        validateFilters(request.filters());
        catalog.resolvePublished(projectId, releaseId);
        UUID runId = releases.selectById(releaseId).getRuntimeRunId();
        if (runId == null) throw new IllegalStateException("发布版本缺少运行标识");
        UUID queryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        queries.reserve(queryId, projectId, releaseId, key, mode.name());
        QueryJobRequest job =
                new QueryJobRequest(
                        runId,
                        key,
                        mode.name(),
                        request.metrics(),
                        request.groupBy(),
                        request.filters(),
                        request.limit(),
                        null,
                        request.startTime(),
                        request.endTime(),
                        request.orderBy() == null ? List.of() : request.orderBy(),
                        null);
        submitRuntime(queryId, job);
        return new QuerySubmission(queryId, releaseId, PENDING);
    }

    public Map<String, Object> queryOptions(
            String projectId, UUID releaseId, List<String> metrics) {
        if (releaseId == null || metrics == null || metrics.isEmpty())
            throw new IllegalArgumentException("指标不能为空");
        catalog.resolvePublished(projectId, releaseId);
        UUID runId = releases.selectById(releaseId).getRuntimeRunId();
        if (runId == null) throw new IllegalStateException("发布版本缺少运行标识");
        JsonNode options = runtime.queryOptions(runId, metrics);
        if (options == null
                || !options.isObject()
                || !options.path("metrics").isArray()
                || !options.path("dimensions").isArray()
                || !options.path("timeDimensions").isArray())
            throw new DbtRuntimeClient.ServiceFailure(false, 200);
        var result = options.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) result)
                .put("releaseId", releaseId.toString());
        return mapper.convertValue(result, Map.class);
    }

    public QuerySubmission dimensionValues(String projectId, DimensionValuesRequest request) {
        if (request == null
                || request.releaseId() == null
                || request.metrics() == null
                || request.metrics().isEmpty()
                || request.dimension() == null
                || request.dimension().isBlank()
                || request.limit() != null && (request.limit() < 1 || request.limit() > MAX_LIMIT))
            throw new IllegalArgumentException("维度值查询参数无效");
        validateFilters(request.filters());
        UUID releaseId = catalog.resolvePublished(projectId, request.releaseId());
        UUID runId = releases.selectById(releaseId).getRuntimeRunId();
        if (runId == null) throw new IllegalStateException("发布版本缺少运行标识");
        UUID queryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        queries.reserve(queryId, projectId, releaseId, key, QueryMode.DIMENSION_VALUES.name());
        submitRuntime(
                queryId,
                new QueryJobRequest(
                        runId,
                        key,
                        QueryMode.DIMENSION_VALUES.name(),
                        request.metrics(),
                        List.of(),
                        request.filters() == null ? List.of() : request.filters(),
                        request.limit(),
                        null,
                        null,
                        null,
                        List.of(),
                        request.dimension()));
        return new QuerySubmission(queryId, releaseId, PENDING);
    }

    public QuerySubmission preview(String projectId, String resourceId, PreviewRequest request) {
        if (request == null
                || request.releaseId() == null
                || request.limit() != null && (request.limit() < 1 || request.limit() > MAX_LIMIT))
            throw new IllegalArgumentException("预览参数无效");
        UUID releaseId = catalog.resolvePublished(projectId, request.releaseId());
        DbtCatalogStore.Resource resource = catalog.resource(projectId, releaseId, resourceId);
        if (!resource.queryable()) throw new IllegalArgumentException("资源不支持预览");
        UUID runId = releases.selectById(releaseId).getRuntimeRunId();
        UUID queryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        queries.reserve(queryId, projectId, releaseId, key, QueryMode.PREVIEW.name());
        submitRuntime(
                queryId,
                new QueryJobRequest(
                        runId,
                        key,
                        QueryMode.PREVIEW.name(),
                        List.of(),
                        List.of(),
                        List.of(),
                        request.limit(),
                        resourceId));
        return new QuerySubmission(queryId, releaseId, PENDING);
    }

    public QueryStatus get(String projectId, UUID queryId) {
        DbtQueryRepository.QueryRecord record = queries.find(projectId, queryId);
        var release = catalog.release(projectId, record.releaseId());
        if (!PENDING.equals(release.cleanupState()))
            throw new ResponseStatusException(HttpStatus.GONE, "发布版本已清理");
        QuerySnapshot snapshot =
                record.runtimeQueryId() == null
                        ? runtime.getQueryByKey(record.idempotencyKey())
                        : runtime.getQuery(record.runtimeQueryId());
        if (snapshot == null || snapshot.state() == null)
            return new QueryStatus(
                    queryId,
                    record.releaseId(),
                    release.targetCommitSha(),
                    record.mode(),
                    record.status(),
                    null,
                    null,
                    record.errorCode(),
                    null,
                    null);
        if (snapshot.queryId() != null && record.runtimeQueryId() == null)
            queries.attach(queryId, snapshot.queryId());
        // 运行服务以 READY 表示查询成功；平台公开契约使用 COMPLETED。
        String status = RUNTIME_READY.equals(snapshot.state()) ? COMPLETED : snapshot.state();
        queries.update(queryId, snapshot.queryId(), status, snapshot.errorCode());
        return new QueryStatus(
                queryId,
                record.releaseId(),
                release.targetCommitSha(),
                record.mode(),
                status,
                plain(snapshot.columns()),
                plain(exactRows(snapshot.columns(), snapshot.rows())),
                snapshot.errorCode(),
                snapshot.truncated(),
                snapshot.durationMs());
    }

    private Object plain(JsonNode node) {
        return node == null ? null : mapper.convertValue(node, Object.class);
    }

    private static void validateFilters(List<QueryFilter> filters) {
        if (filters == null) return;
        if (filters.size() > 20) throw new IllegalArgumentException("筛选条件过多");
        for (QueryFilter filter : filters) {
            if (filter == null
                    || filter.field() == null
                    || filter.field().isBlank()
                    || !FILTER_OPERATORS.contains(filter.operator())
                    || filter.value() == null) throw new IllegalArgumentException("筛选条件无效");
            if ("IN".equals(filter.operator())) {
                if (!filter.value().isArray()
                        || filter.value().isEmpty()
                        || filter.value().size() > 100)
                    throw new IllegalArgumentException("IN 筛选值超限");
            } else if (filter.value().isContainerNode())
                throw new IllegalArgumentException("筛选值必须是标量");
        }
    }

    private void submitRuntime(UUID queryId, QueryJobRequest job) {
        try {
            QueryReceipt receipt = runtime.submitQuery(job);
            if (receipt == null || receipt.queryId() == null)
                throw new DbtRuntimeClient.ServiceFailure(false, 200);
            queries.attach(queryId, receipt.queryId());
        } catch (DbtRuntimeClient.ServiceFailure failure) {
            if (!failure.transientFailure()) {
                queries.update(queryId, null, FAILED, "SERVICE_REJECTED");
                throw failure;
            }
            // 幂等键已经落库，超时后的 GET 会从服务按键恢复任务。
        }
    }

    /** JSON Number 会在浏览器丢精度，按列类型将高精度数值变为字符串。 */
    private JsonNode exactRows(JsonNode columns, JsonNode rows) {
        if (columns == null || !columns.isArray() || rows == null || !rows.isArray()) return rows;
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (JsonNode row : rows) {
            if (!row.isArray()) {
                result.add(row);
                continue;
            }
            ArrayNode converted = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < row.size(); i++) {
                JsonNode cell = row.get(i);
                String type = i < columns.size() ? columns.get(i).path("type").asText("") : "";
                if (cell.isNumber()
                        && (type.startsWith("DECIMAL")
                                || type.startsWith("NUMERIC")
                                || type.equals("BIGINT")))
                    converted.add(cell.decimalValue().toPlainString());
                else converted.add(cell);
            }
            result.add(converted);
        }
        return result;
    }
}
