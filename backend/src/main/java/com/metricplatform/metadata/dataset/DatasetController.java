package com.metricplatform.metadata.dataset;

import static com.metricplatform.metadata.dataset.DatasetDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DatasetController {
    private final DatasetService datasets;
    private final SchemaDriftService schemaDrift;

    @GetMapping
    public PageResponse<DatasetResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID dataSourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("invalid page or size");
        }
        return datasets.list(q, dataSourceId, page, size);
    }

    @PostMapping
    public ResponseEntity<DatasetResponse> create(
            @Valid @RequestBody CreateDatasetRequest request) {
        return ResponseEntity.status(201).body(datasets.create(request));
    }

    @GetMapping("/{id}")
    public DatasetResponse get(@PathVariable UUID id) {
        return datasets.get(id);
    }

    @PatchMapping("/{id}")
    public DatasetResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateDatasetRequest request) {
        return datasets.update(id, request);
    }

    @GetMapping("/{id}/fields")
    public List<DatasetFieldResponse> fields(@PathVariable UUID id) {
        return datasets.fields(id);
    }

    @GetMapping("/{id}/deletion-impact")
    public DeletionImpactResponse deletionImpact(@PathVariable UUID id) {
        return datasets.deletionImpact(id);
    }

    @PostMapping("/{id}:detect-field-changes")
    public SchemaDiffResponse detectFieldChanges(@PathVariable UUID id) {
        return schemaDrift.detect(id);
    }

    @PostMapping("/{id}:sync-fields")
    public SchemaDiffResponse syncFields(
            @PathVariable UUID id, @Valid @RequestBody SyncFieldsRequest request) {
        return schemaDrift.sync(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean cascade) {
        datasets.delete(id, cascade);
        return ResponseEntity.noContent().build();
    }
}
