package com.metricplatform.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.persistence.entity.AuditEventEntity;
import com.metricplatform.persistence.service.AuditEventPersistenceService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class PlatformIdentityAuditWriter {
    private final AuditEventPersistenceService events;
    private final ObjectMapper objectMapper;

    public PlatformIdentityAuditWriter(
            AuditEventPersistenceService events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public void write(
            PlatformPrincipal actor,
            String action,
            String resourceType,
            UUID resourceId,
            String outcome,
            Map<String, Object> detail) {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(UUID.randomUUID());
        event.setActorId(actor == null ? "bootstrap" : actor.identityId().toString());
        event.setEffectiveIdentityId(resourceId);
        event.setClientId(actor == null ? "metric-platform-bootstrap" : actor.clientId());
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setOutcome(outcome);
        event.setDetailJson(json(detail));
        events.save(event);
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize identity audit detail", failure);
        }
    }
}
