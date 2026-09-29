package com.metricplatform.identity.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.identity.PlatformIdentityRepository;
import com.metricplatform.security.PlatformPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class PlatformIdentityAuditWriter {
    private final PlatformIdentityRepository events;
    private final ObjectMapper objectMapper;

    public PlatformIdentityAuditWriter(
            PlatformIdentityRepository events, ObjectMapper objectMapper) {
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
        events.writeAudit(
                actor == null ? "bootstrap" : actor.identityId().toString(),
                action,
                resourceType,
                resourceId,
                outcome,
                json(detail));
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize identity audit detail", failure);
        }
    }
}
