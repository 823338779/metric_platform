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
            @NotEmpty List<@Valid FieldPairRequest> fieldPairs) {}

    public record UpdateRelationshipRequest(
            @NotBlank String name,
            @NotNull Cardinality cardinality,
            @NotEmpty List<@Valid FieldPairRequest> fieldPairs,
            long version) {}

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
            long version) {}
}
