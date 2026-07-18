package com.metricplatform.query;

import java.util.List;
import java.util.UUID;

public class QueryValidationException extends IllegalArgumentException {
    private final String code;
    private final QueryDtos.SemanticObjectType objectType;
    private final UUID objectId;
    private final List<QueryDtos.RelationshipPathSummary> candidatePaths;

    public QueryValidationException(String code, String message) {
        this(code, message, null, null, List.of());
    }

    public QueryValidationException(
            String code,
            String message,
            QueryDtos.SemanticObjectType objectType,
            UUID objectId,
            List<QueryDtos.RelationshipPathSummary> candidatePaths) {
        super(message);
        this.code = code;
        this.objectType = objectType;
        this.objectId = objectId;
        this.candidatePaths = List.copyOf(candidatePaths);
    }

    public String code() { return code; }
    public QueryDtos.SemanticObjectType objectType() { return objectType; }
    public UUID objectId() { return objectId; }
    public List<QueryDtos.RelationshipPathSummary> candidatePaths() { return candidatePaths; }
}
