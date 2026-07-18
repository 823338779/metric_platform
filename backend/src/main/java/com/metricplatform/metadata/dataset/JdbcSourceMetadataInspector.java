package com.metricplatform.metadata.dataset;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.source.JdbcMetadataService;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JdbcSourceMetadataInspector implements SourceMetadataInspector {
    private final DataSourceMapper sources;
    private final JdbcMetadataService metadata;

    @Override
    public SourceObjectDefinition inspectObject(UUID sourceId, String schema, String objectName) {
        DataSourceEntity source =
                java.util.Optional.ofNullable(sources.selectById(sourceId))
                        .orElseThrow(() -> new NoSuchElementException("data source not found"));
        boolean postgres = "POSTGRESQL".equals(source.getDatabaseType());
        String catalog =
                postgres ? first(source.getDefaultCatalog(), source.getDatabaseName()) : schema;
        String namespace = postgres ? schema : schema;
        try {
            var object = metadata.requireObject(sourceId, catalog, namespace, objectName);
            var columns =
                    metadata.fields(sourceId, catalog, namespace, objectName).stream()
                            .map(
                                    column ->
                                            new SourceColumn(
                                                    column.physicalName(),
                                                    column.physicalType(),
                                                    column.ordinal()))
                            .sorted(Comparator.comparingInt(SourceColumn::ordinal))
                            .toList();
            return new SourceObjectDefinition(
                    schema, object.objectName(), object.objectType(), columns);
        } catch (SQLException exception) {
            throw new IllegalStateException("source metadata inspection failed", exception);
        }
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
