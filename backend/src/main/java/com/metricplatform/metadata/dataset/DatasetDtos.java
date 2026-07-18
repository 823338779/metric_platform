package com.metricplatform.metadata.dataset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public final class DatasetDtos {
    private DatasetDtos() {}

    public record CreateDatasetRequest(
            @NotNull UUID dataSourceId,
            @NotBlank String name,
            String description,
            @NotBlank String schemaName,
            @NotBlank String objectName,
            @NotEmpty List<@NotBlank String> selectedPhysicalFields,
            String businessGrain,
            UUID defaultTimeDimensionId,
            String freshnessPolicy,
            String qualityStatus,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            List<String> aliases) {
        public CreateDatasetRequest(
                UUID dataSourceId, String name, String description, String schemaName,
                String objectName, List<String> selectedPhysicalFields) {
            this(dataSourceId, name, description, schemaName, objectName, selectedPhysicalFields,
                    null, null, null, "UNKNOWN", null, null, "ACTIVE", "UNCERTIFIED", List.of());
        }
    }

    public record FieldUpdate(
            @NotNull UUID fieldId,
            @NotBlank String displayName,
            boolean selected,
            String description,
            String sensitivityLevel) {
        public FieldUpdate(UUID fieldId, String displayName, boolean selected) {
            this(fieldId, displayName, selected, null, "INTERNAL");
        }
    }

    public record UpdateDatasetRequest(
            @NotBlank String name,
            String description,
            FilterExpression filterExpression,
            List<@Valid FieldUpdate> fieldUpdates,
            @Min(0) long version,
            String businessGrain,
            UUID defaultTimeDimensionId,
            String freshnessPolicy,
            String qualityStatus,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            List<String> aliases) {
        public UpdateDatasetRequest(
                String name, String description, FilterExpression filterExpression,
                List<FieldUpdate> fieldUpdates, long version) {
            this(name, description, filterExpression, fieldUpdates, version, null, null, null,
                    "UNKNOWN", null, null, "ACTIVE", "UNCERTIFIED", null);
        }
    }

    public record DatasetFieldResponse(
            UUID id,
            String physicalName,
            String displayName,
            String physicalType,
            boolean selected,
            long version,
            String description,
            String sensitivityLevel) {
        public DatasetFieldResponse(
                UUID id, String physicalName, String displayName, String physicalType,
                boolean selected, long version) {
            this(id, physicalName, displayName, physicalType, selected, version, null, "INTERNAL");
        }
    }

    public record DatasetResponse(
            UUID id,
            UUID dataSourceId,
            String name,
            String description,
            String schemaName,
            String objectName,
            String objectType,
            FilterExpression filterExpression,
            long version,
            List<DatasetFieldResponse> fields,
            String businessGrain,
            UUID defaultTimeDimensionId,
            String freshnessPolicy,
            String qualityStatus,
            String domain,
            String owner,
            String status,
            String certificationStatus,
            List<String> aliases) {
        public DatasetResponse(
                UUID id, UUID dataSourceId, String name, String description, String schemaName,
                String objectName, String objectType, FilterExpression filterExpression,
                long version, List<DatasetFieldResponse> fields) {
            this(id, dataSourceId, name, description, schemaName, objectName, objectType,
                    filterExpression, version, fields, null, null, null, "UNKNOWN", null, null,
                    "ACTIVE", "UNCERTIFIED", List.of());
        }
    }

    public record PageResponse<T>(List<T> items, long total, int page, int size) {}

    public record DeletionImpactResponse(
            int fieldCount, int metricCount, int dimensionCount, int relationshipCount) {}

    public record FieldChange(
            UUID fieldId, String physicalName, String previousType, String currentType) {}

    public record SchemaDiffResponse(
            List<FieldChange> added,
            List<FieldChange> removed,
            List<FieldChange> typeChanged,
            List<String> blockingImpacts,
            String fingerprint) {}

    public record SyncFieldsRequest(@Min(0) long version, @NotBlank String fingerprint) {}

    public record PageRequest(
            String q, UUID dataSourceId, @Min(0) int page, @Min(1) @Max(200) int size) {}
}
