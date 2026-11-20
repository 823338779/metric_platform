package com.metricplatform.ontology.api;

import com.metricplatform.catalog.OntologyBindingService.Columns;
import com.metricplatform.catalog.OntologyBindingService.Datasets;
import com.metricplatform.ontology.OntologyException;
import com.metricplatform.ontology.OntologyProblem;
import com.metricplatform.ontology.OntologyService;
import com.metricplatform.ontology.OntologyService.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dbt/environments/{environmentId}/ontology")
@RequiredArgsConstructor
public class OntologyController {
    private final OntologyService service;

    @GetMapping
    public View get(@PathVariable UUID environmentId) {
        return service.read(environmentId);
    }

    @GetMapping("/datasets")
    public Datasets datasets(@PathVariable UUID environmentId) {
        return service.datasets(environmentId);
    }

    @GetMapping("/datasets/{assetId}/columns")
    public Columns columns(
            @PathVariable UUID environmentId,
            @PathVariable UUID assetId,
            @RequestParam UUID buildId) {
        return service.columns(environmentId, assetId, buildId);
    }

    @PostMapping("/objects")
    @ResponseStatus(HttpStatus.CREATED)
    public Saved createObject(@PathVariable UUID environmentId, @RequestBody ObjectWrite write) {
        return service.saveObject(environmentId, null, write);
    }

    @PutMapping("/objects/{id}")
    public Saved updateObject(
            @PathVariable UUID environmentId,
            @PathVariable UUID id,
            @RequestBody ObjectWrite write) {
        return service.saveObject(environmentId, id, write);
    }

    @DeleteMapping("/objects/{id}")
    public Saved deleteObject(
            @PathVariable UUID environmentId,
            @PathVariable UUID id,
            @RequestParam long expectedRevision) {
        return service.deleteObject(environmentId, id, expectedRevision);
    }

    @PostMapping("/relations")
    @ResponseStatus(HttpStatus.CREATED)
    public Saved createRelation(
            @PathVariable UUID environmentId, @RequestBody RelationWrite write) {
        return service.saveRelation(environmentId, null, write);
    }

    @PutMapping("/relations/{id}")
    public Saved updateRelation(
            @PathVariable UUID environmentId,
            @PathVariable UUID id,
            @RequestBody RelationWrite write) {
        return service.saveRelation(environmentId, id, write);
    }

    @DeleteMapping("/relations/{id}")
    public Saved deleteRelation(
            @PathVariable UUID environmentId,
            @PathVariable UUID id,
            @RequestParam long expectedRevision) {
        return service.deleteRelation(environmentId, id, expectedRevision);
    }

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ProblemDetail> graphFailure(OntologyException ex) {
        var status =
                switch (ex.code()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        var body = new OntologyProblem(status, ex.code().name(), ex.getMessage()).getBody();
        body.setProperty("references", ex.references());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
