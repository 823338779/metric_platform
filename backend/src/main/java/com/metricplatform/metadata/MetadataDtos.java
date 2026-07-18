package com.metricplatform.metadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public final class MetadataDtos {
    private MetadataDtos() {}

    public record FieldRequest(
            @NotBlank String physicalName,
            @NotBlank String displayName,
            @NotBlank String starrocksType,
            boolean dimensionEnabled,
            boolean metricEnabled) {}

    public record CreateDatasetRequest(
            @NotNull UUID dataSourceId,
            @NotBlank String name,
            @NotBlank String schemaName,
            @NotBlank String objectName,
            @NotEmpty List<@Valid FieldRequest> fields) {}

    public record DimensionRequest(
            @NotNull UUID datasetId,
            @NotNull UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName) {}

    public record MetricRequest(
            @NotNull UUID datasetId,
            UUID datasetFieldId,
            @NotBlank String name,
            @NotBlank String displayName,
            @NotBlank String aggregation) {}
}
