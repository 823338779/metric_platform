package com.metricplatform.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import com.metricplatform.query.QueryDtos;
import com.metricplatform.security.PlatformPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

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
            QueryPermission permission,
            UUID resourceId,
            String outcome,
            QueryDtos.QueryRequest request,
            Set<UUID> datasetIds,
            long durationMs) {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(UUID.randomUUID());
        event.setActorId(principal == null ? "anonymous" : principal.identityId().toString());
        event.setEffectiveIdentityId(principal == null ? null : principal.identityId());
        event.setClientId(principal == null ? null : principal.clientId());
        event.setAction("METRIC_QUERY_" + permission.name());
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
        return detail;
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
