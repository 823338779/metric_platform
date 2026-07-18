package com.metricplatform.metadata.dataset;

import java.util.List;
import java.util.UUID;

public interface SourceMetadataInspector {
    SourceObjectDefinition inspectObject(UUID sourceId, String schema, String objectName);

    record SourceObjectDefinition(
            String schema, String name, String type, List<SourceColumn> columns) {
        public SourceObjectDefinition {
            columns = List.copyOf(columns);
        }
    }

    record SourceColumn(String name, String type, int ordinal) {}
}
