package com.metricplatform.semantic;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SemanticController {
    private final SemanticDefinitionService semantics;

    @GetMapping("/api/v1/metrics")
    public SemanticDtos.PageResponse<SemanticDtos.MetricResponse> listMetrics(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID datasetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return semantics.listMetrics(q, datasetId, page, size);
    }

    @PostMapping("/api/v1/metrics")
    public ResponseEntity<SemanticDtos.MetricResponse> createMetric(
            @Valid @RequestBody SemanticDtos.CreateMetricRequest request) {
        return ResponseEntity.status(201).body(semantics.createMetric(request));
    }

    @GetMapping("/api/v1/metrics/{id}")
    public SemanticDtos.MetricResponse getMetric(@PathVariable UUID id) {
        return semantics.getMetric(id);
    }

    @PatchMapping("/api/v1/metrics/{id}")
    public SemanticDtos.MetricResponse updateMetric(
            @PathVariable UUID id, @Valid @RequestBody SemanticDtos.UpdateMetricRequest request) {
        return semantics.updateMetric(id, request);
    }

    @DeleteMapping("/api/v1/metrics/{id}")
    public ResponseEntity<Void> deleteMetric(@PathVariable UUID id) {
        semantics.deleteMetric(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/metrics/{id}/deletion-impact")
    public SemanticDtos.DeletionImpactResponse metricDeletionImpact(@PathVariable UUID id) {
        return semantics.metricDeletionImpact(id);
    }

    @GetMapping("/api/v1/dimensions")
    public SemanticDtos.PageResponse<SemanticDtos.DimensionResponse> listDimensions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID datasetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return semantics.listDimensions(q, datasetId, page, size);
    }

    @PostMapping("/api/v1/dimensions")
    public ResponseEntity<SemanticDtos.DimensionResponse> createDimension(
            @Valid @RequestBody SemanticDtos.CreateDimensionRequest request) {
        return ResponseEntity.status(201).body(semantics.createDimension(request));
    }

    @GetMapping("/api/v1/dimensions/{id}")
    public SemanticDtos.DimensionResponse getDimension(@PathVariable UUID id) {
        return semantics.getDimension(id);
    }

    @PatchMapping("/api/v1/dimensions/{id}")
    public SemanticDtos.DimensionResponse updateDimension(
            @PathVariable UUID id,
            @Valid @RequestBody SemanticDtos.UpdateDimensionRequest request) {
        return semantics.updateDimension(id, request);
    }

    @DeleteMapping("/api/v1/dimensions/{id}")
    public ResponseEntity<Void> deleteDimension(@PathVariable UUID id) {
        semantics.deleteDimension(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/dimensions/{id}/deletion-impact")
    public SemanticDtos.DeletionImpactResponse dimensionDeletionImpact(@PathVariable UUID id) {
        return semantics.dimensionDeletionImpact(id);
    }
}
