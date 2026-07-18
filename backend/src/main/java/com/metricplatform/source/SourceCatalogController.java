package com.metricplatform.source;

import static com.metricplatform.source.DataSourceDtos.*;

import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/data-sources")
@RequiredArgsConstructor
public class SourceCatalogController {
    private final DataSourceManagementService management;
    private final JdbcMetadataService metadata;

    @GetMapping
    public List<DataSourceSummary> dataSources(
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return management.list(includeDeleted);
    }

    @PostMapping
    public ResponseEntity<DataSourceDetail> create(
            @Valid @RequestBody CreateDataSourceRequest request) {
        return ResponseEntity.status(201).body(management.create(request));
    }

    @GetMapping("/{id}")
    public DataSourceDetail get(@PathVariable UUID id) {
        return management.get(id);
    }

    @PatchMapping("/{id}")
    public DataSourceDetail update(
            @PathVariable UUID id, @Valid @RequestBody UpdateDataSourceRequest request) {
        return management.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        management.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}:test")
    public ConnectionTestResponse test(@PathVariable UUID id) {
        return management.test(id);
    }

    @PostMapping("/{id}:activate")
    public DataSourceDetail activate(@PathVariable UUID id) {
        return management.activate(id);
    }

    @PostMapping("/{id}:deactivate")
    public DataSourceDetail deactivate(@PathVariable UUID id) {
        return management.deactivate(id);
    }

    @GetMapping("/{id}/deletion-impact")
    public DataSourceDeletionImpact deletionImpact(@PathVariable UUID id) {
        return management.deletionImpact(id);
    }

    @GetMapping("/{id}/catalogs")
    public List<CatalogResponse> catalogs(@PathVariable UUID id) throws SQLException {
        return metadata.catalogs(id);
    }

    @GetMapping("/{id}/schemas")
    public List<SchemaResponse> schemas(
            @PathVariable UUID id, @RequestParam(required = false) String catalogName)
            throws SQLException {
        return metadata.schemas(id, catalogName);
    }

    @GetMapping("/{id}/objects")
    public List<SourceObjectResponse> objects(
            @PathVariable UUID id,
            @RequestParam(required = false) String catalogName,
            @RequestParam(required = false) String schemaName)
            throws SQLException {
        return metadata.objects(id, catalogName, schemaName);
    }

    @GetMapping("/{id}/fields")
    public List<SourceFieldResponse> fields(
            @PathVariable UUID id,
            @RequestParam(required = false) String catalogName,
            @RequestParam(required = false) String schemaName,
            @RequestParam String objectName)
            throws SQLException {
        return metadata.fields(id, catalogName, schemaName, objectName);
    }

    @GetMapping("/{id}/preview")
    public PreviewResponse preview(
            @PathVariable UUID id,
            @RequestParam(required = false) String catalogName,
            @RequestParam(required = false) String schemaName,
            @RequestParam String objectName,
            @RequestParam(defaultValue = "20") int limit)
            throws SQLException {
        return metadata.preview(id, catalogName, schemaName, objectName, limit);
    }
}
