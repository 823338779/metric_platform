package com.metricplatform.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public final class MetricQueryAuditWriter {
    private final AuditEventPersistenceService events;
    private final ObjectMapper objectMapper;

    public MetricQueryAuditWriter(AuditEventPersistenceService events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public void write(
            PlatformPrincipal principal,
            QueryOperation operation,
            UUID resourceId,
            String outcome,
            QueryDtos.QueryRequest request,
            Set<UUID> datasetIds,
            long durationMs) {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(UUID.randomUUID());
        event.setActorId(principal == null ? "anonymous" : principal.identityId().toString());
        event.setEffectiveIdentityId(principal == null ? null : principal.identityId());
        event.setAction("METRIC_QUERY_" + operation.name());
        event.setResourceType("semantic_query");
        event.setResourceId(resourceId);
        event.setOutcome(outcome);
        event.setDetailJson(json(detail(request, datasetIds, durationMs)));
        events.save(event);
    }

    private static Map<String, Object> detail(
            QueryDtos.QueryRequest request, Set<UUID> datasetIds, long durationMs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("datasetIds", datasetIds);
        detail.put("metricIds", request.metricIds());
        detail.put(
                "dimensionIds",
                request.dimensions().stream()
                        .map(QueryDtos.DimensionSelection::dimensionId)
                        .toList());
        detail.put(
                "filterDimensionIds",
                request.filters().stream().map(QueryDtos.DimensionFilter::dimensionId).toList());
        detail.put("relationshipIds", request.relationshipIds());
        detail.put("durationMs", durationMs);
        addRequestCorrelation(detail);
        return detail;
    }

    private static void addRequestCorrelation(Map<String, Object> detail) {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) return;
        putHeader(detail, "correlationId", attributes, "X-Correlation-Id");
        putHeader(detail, "agentSessionId", attributes, "X-Agent-Session-Id");
        putHeader(detail, "agentRunId", attributes, "X-Agent-Run-Id");
        putHeader(detail, "traceparent", attributes, "traceparent");
    }

    private static void putHeader(
            Map<String, Object> detail,
            String key,
            ServletRequestAttributes attributes,
            String header) {
        String value = attributes.getRequest().getHeader(header);
        if (value == null || value.isBlank() || value.length() > 160) return;
        if (value.matches("[A-Za-z0-9._:-]+")) detail.put(key, value);
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to serialize metric query audit detail", failure);
        }
    }
}
