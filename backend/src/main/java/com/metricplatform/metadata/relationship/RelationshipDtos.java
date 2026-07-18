package com.metricplatform.metadata.relationship;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class RelationshipDtos {
    private RelationshipDtos() {}

    public enum Cardinality {
        ONE_TO_ONE,
        MANY_TO_ONE
    }

    public record FieldPairRequest(@NotNull UUID sourceFieldId, @NotNull UUID targetFieldId) {}

    public record CreateRelationshipRequest(
            @NotBlank String name,
            @NotNull UUID sourceDatasetId,
            @NotNull UUID targetDatasetId,
            @NotNull Cardinality cardinality,
            @NotEmpty List<@Valid FieldPairRequest> fieldPairs,
            String description,
            String displayPath,
            String semanticRole,
            Boolean fanoutSafe,
            String status) {
        public CreateRelationshipRequest(
                String name, UUID sourceDatasetId, UUID targetDatasetId, Cardinality cardinality,
                List<FieldPairRequest> fieldPairs) {
            this(name, sourceDatasetId, targetDatasetId, cardinality, fieldPairs, null, null, null,
                    true, "ACTIVE");
        }
    }

    public record UpdateRelationshipRequest(
            @NotBlank String name,
            @NotNull Cardinality cardinality,
            @NotEmpty List<@Valid FieldPairRequest> fieldPairs,
            long version,
            String description,
            String displayPath,
            String semanticRole,
            Boolean fanoutSafe,
            String status) {
        public UpdateRelationshipRequest(
                String name, Cardinality cardinality, List<FieldPairRequest> fieldPairs,
                long version) {
            this(name, cardinality, fieldPairs, version, null, null, null, true, "ACTIVE");
        }
    }

    public record RelationshipResponse(
            UUID id,
            String name,
            UUID sourceDatasetId,
            UUID targetDatasetId,
            Cardinality cardinality,
            String joinType,
            List<FieldPairRequest> fieldPairs,
            String validationStatus,
            LocalDateTime lastValidatedAt,
            long version,
            String description,
            String displayPath,
            String semanticRole,
            boolean fanoutSafe,
            String status) {
        public RelationshipResponse(
                UUID id, String name, UUID sourceDatasetId, UUID targetDatasetId,
                Cardinality cardinality, String joinType, List<FieldPairRequest> fieldPairs,
                String validationStatus, LocalDateTime lastValidatedAt, long version) {
            this(id, name, sourceDatasetId, targetDatasetId, cardinality, joinType, fieldPairs,
                    validationStatus, lastValidatedAt, version, null, null, null, true, "ACTIVE");
        }
    }
}
