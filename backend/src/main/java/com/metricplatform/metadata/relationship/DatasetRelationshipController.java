package com.metricplatform.metadata.relationship;

import jakarta.validation.Valid;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dataset-relationships")
@RequiredArgsConstructor
public class DatasetRelationshipController {
    private final DatasetRelationshipService relationships;

    @GetMapping
    public List<RelationshipDtos.RelationshipResponse> list(
            @RequestParam(required = false) UUID datasetId) {
        return relationships.list(datasetId);
    }

    @PostMapping
    public ResponseEntity<RelationshipDtos.RelationshipResponse> create(
            @Valid @RequestBody RelationshipDtos.CreateRelationshipRequest request) {
        return ResponseEntity.status(201).body(relationships.create(request));
    }

    @GetMapping("/{id}")
    public RelationshipDtos.RelationshipResponse get(@PathVariable UUID id) {
        return relationships.get(id);
    }

    @PatchMapping("/{id}")
    public RelationshipDtos.RelationshipResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody RelationshipDtos.UpdateRelationshipRequest request) {
        return relationships.update(id, request);
    }

    @PostMapping("/{id}:revalidate")
    public RelationshipDtos.RelationshipResponse revalidate(@PathVariable UUID id) {
        return relationships.revalidate(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        relationships.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deletion-impact")
    public RelationshipDtos.DeletionImpactResponse deletionImpact(@PathVariable UUID id) {
        return relationships.deletionImpact(id);
    }
}
